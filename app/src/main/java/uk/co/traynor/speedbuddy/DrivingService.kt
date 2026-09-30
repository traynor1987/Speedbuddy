package uk.co.traynor.speedbuddy

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Process-local live state. The service owns location; activity recreation simply re-subscribes.
data class DriveState(
    val active: Boolean = false, val speedMph: Double? = null, val limitMph: Int? = null,
    val fix: Fix? = null, val road: RoadMatch? = null, val alert: Alert? = null,
    val decision: CameraDecision = CameraDecision(null, null, false, "Waiting for GPS"),
    val dataAgeMs: Long? = null, val status: String = "Start driving mode", val overspeed: Boolean = false,
    val mapStatus: String = "No map request yet",
    val upcoming: UpcomingLimit? = null, val publicCameraCount: Int = 0, val userCameraCount: Int = 0,
    val importedCameraCount: Int = 0, val importedNearbyCount: Int = 0,
    val turns: List<TurnLimit> = emptyList(),
    val alertPositionFresh: Boolean = true,
    val averageSection: ActiveAverageSection? = null,
)
object DriveBus { private val mutable = MutableStateFlow(DriveState()); val state = mutable.asStateFlow(); fun set(state: DriveState) { mutable.value = state } }

class DrivingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val fixes = Channel<Fix>(Channel.CONFLATED)
    private lateinit var locationManager: LocationManager
    private lateinit var db: CameraDb
    private lateinit var osm: OsmDataSource
    private val speedFilter = SpeedFilter(); private val matcher = RoadMatcher()
    private val limitStabilizer = RoadLimitStabilizer()
    private val upcomingDetector = UpcomingLimitDetector()
    private val turnDetector = TurnLimitDetector()
    private val detector = CameraApproachDetector(); private val overspeed = OverspeedGate()
    private val limits: SpeedLimitProvider = OsmSpeedLimitProvider()
    private var snapshot: OsmSnapshot? = null
    private var previousSnapshot: OsmSnapshot? = null
    private var fetching = false; private var lastAttempt = 0L
    private var mapStatus = "Cached map data"
        private lateinit var cameraVoice: CameraVoice
    private var roadRevision = -1L
    private var indexedRoadRevision = -1L
    private var roadOverrides: Map<String, RoadLimitCorrection> = emptyMap()
    private var cameraRevision = -1L
    private var cameraSnapshot: OsmSnapshot? = null
    private var effectiveCameraCache: List<Camera> = emptyList()
    private var cameraCenter: GeoPoint? = null
    private var importedCount = 0
    private var userCount = 0
    private var cachedRoadSnapshot: OsmSnapshot? = null
    private var lastCacheLookupPoint: GeoPoint? = null
    private var lastCacheLookupRevision = -1L
    private var roadIndex: RoadSpatialIndex? = null
    private var correctedRoadCache: List<Road> = emptyList()
    private val limitVoiceGate = LimitChangeGate()
    private val sectionTracker=AverageSectionTracker()
    private var tick: Job? = null
    private var lastMobilePrune = 0L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        db = CameraDb(this); osm = OsmDataSource(this)
        scope.launch {
            snapshot = osm.cached()
            for (fix in fixes) {
                try { processFix(fix) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.e("SpeedBuddy", "Driving data processing failed",error)
                    val live=DriveBus.state.value
                    DriveBus.set(live.copy(limitMph=null,road=null,upcoming=null,turns=emptyList(),status="Road data unavailable · GPS remains active"))
                }
            }
        }
        cameraVoice = CameraVoice(this) { signal(true, false) }
        val channel = NotificationChannel("drive", "Driving mode", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val stop = Intent(this, DrivingService::class.java).setAction("STOP")
        val pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "drive").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setContentTitle("Speed Buddy is active").setContentText("GPS speed and camera alerts")
            .addAction(Notification.Action.Builder(null, "Stop", pending).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(42, notification)
        } catch (failure: RuntimeException) {
            DriveBus.set(DriveState(status="Driving mode needs location permission while the app is open"))
            stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DriveBus.set(DriveState(status = "Precise location required")); stopSelf(); return START_NOT_STICKY
        }
        if (tick == null) {
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
            catch (_: Exception) { DriveBus.set(DriveState(status = "GPS unavailable")); stopSelf(); return START_NOT_STICKY }
            tick = scope.launch { while (isActive) { delay(1000); var state = DriveBus.state.value
                val wallNow=System.currentTimeMillis()
                val settings=getSharedPreferences("settings",Context.MODE_PRIVATE)
                if(cameraRevision!=OwnerDataRevision.cameras && state.alert?.camera?.type==CameraType.MOBILE) {
                    val report=state.alert!!.camera.mobileReport!!
                    val stored=MobileReportStore(db).activeInBounds(report.point.lat-.00001,report.point.lon-.00001,
                        report.point.lat+.00001,report.point.lon+.00001,wallNow).firstOrNull { it.id==report.id }
                    state=if(stored==null) state.copy(alert=null,decision=CameraDecision(null,null,false,"Mobile report removed or expired"))
                        else state.copy(alert=state.alert!!.copy(camera=stored.asCamera()))
                    DriveBus.set(state)
                }
                state.alert?.let { alert ->
                    if (!cameraEnabled(alert.camera,settings) || (alert.camera.type==CameraType.MOBILE && alert.camera.mobileReport?.activeAt(wallNow)!=true)) {
                        state=state.copy(alert=null,decision=CameraDecision(null,null,false,"Camera warning ended"))
                        DriveBus.set(state)
                    }
                }
                if (wallNow-lastMobilePrune>=60_000) { runCatching { MobileReportStore(db).prune(wallNow) };lastMobilePrune=wallNow }
                if(cameraRevision!=OwnerDataRevision.cameras && state.fix?.let { SystemClock.elapsedRealtime()-it.elapsedMs in 0..5_000 }==true)
                    fixes.trySend(state.fix!!)

                if (state.fix != null && SystemClock.elapsedRealtime() - state.fix.elapsedMs > 5000) {
                    speedFilter.current(SystemClock.elapsedRealtime()); matcher.reset(); limitStabilizer.reset()
                    DriveBus.set(state.copy(speedMph = null, limitMph = null, road = null, alertPositionFresh = false,
                        upcoming = null, turns = emptyList(),averageSection=state.averageSection?.copy(remainingM=Double.NaN), status = "GPS signal lost"))
                }
            } }
        }
        DriveBus.set(DriveBus.state.value.copy(active = true, status = "Waiting for GPS"))
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val fix = Fix(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble(),
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
            if (location.hasBearing()) location.bearing.toDouble() else null, location.elapsedRealtimeNanos / 1_000_000)
        if (now - fix.elapsedMs !in 0..5000) return
        fixes.trySend(fix)
    }
    private suspend fun processFix(fix: Fix) {
        val now = SystemClock.elapsedRealtime()
        val speed = speedFilter.update(fix, now)
        val wallNow = System.currentTimeMillis()
        if ((snapshot?.usable(fix.point,wallNow)!=true || roadRevision != OwnerDataRevision.roads) &&
            (lastCacheLookupRevision!=OwnerDataRevision.roads || lastCacheLookupPoint?.let { Geo.distance(it,fix.point)>250 }!=false)) {
            osm.cached(fix.point)?.let { region -> if (region != snapshot) { previousSnapshot=snapshot; snapshot=region } }
            lastCacheLookupPoint=fix.point;lastCacheLookupRevision=OwnerDataRevision.roads
        }
        val cached = listOfNotNull(snapshot, previousSnapshot)
            .filter { it.usable(fix.point, wallNow) }
            .minByOrNull { Geo.distance(it.center, fix.point) }
        if (roadRevision != OwnerDataRevision.roads) {
            roadOverrides = db.roadCorrections().associateBy { it.id }
            roadRevision = OwnerDataRevision.roads
        }
        if (cached !== cachedRoadSnapshot || roadRevision != indexedRoadRevision) {
            correctedRoadCache=cached?.roads.orEmpty().map { road -> roadOverrides[road.id]?.apply(road) ?: road }
            roadIndex=RoadSpatialIndex(correctedRoadCache); cachedRoadSnapshot=cached; indexedRoadRevision=roadRevision
        }
        val correctedRoads = if (cached == null) null else roadIndex?.nearby(fix.point)
        val road = if (correctedRoads != null && fix.accuracyM <= 35) matcher.match(fix, correctedRoads) else null
        val limit = if (cached != null) limitStabilizer.resolve(fix, road, limits.limit(road), now, correctedRoads.orEmpty())
            else { limitStabilizer.reset(); null }
        val upcoming = correctedRoads?.let { upcomingDetector.detect(fix, road, limit, it) }
        val turns = correctedRoads?.let { turnDetector.detect(fix, road, limit, it) }.orEmpty()
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val publicCameras = listOfNotNull(snapshot, previousSnapshot).filter { it.usable(fix.point, wallNow) }
            .flatMap { it.cameras }.distinctBy { it.id }
        if (cameraRevision != OwnerDataRevision.cameras || cameraCenter?.let { Geo.distance(it,fix.point)>250 } != false || cached !== cameraSnapshot) {
            effectiveCameraCache=db.effectiveInBounds(fix.point.lat-.015,fix.point.lon-.025,
                fix.point.lat+.015,fix.point.lon+.025,publicCameras)
            importedCount=db.importedInfo()?.count ?: 0
            userCount=db.ownerCameraCount()
            cameraCenter=fix.point; cameraRevision=OwnerDataRevision.cameras; cameraSnapshot=cached
        }
        val cameras=effectiveCameraCache
        val enabled = cameras.filter { cameraEnabled(it,settings) }
        val (alert, decision) = detector.evaluate(fix, road, enabled, speed,correctedRoads.orEmpty(),wallNow)
        val section=if(settings.getBoolean("speedCamera",true)) sectionTracker.update(fix,road,cached?.averageSections.orEmpty()) else null
        val newCamera = alert != null && decision.reason == "New approach"
        val changedLimit=limitVoiceGate.update(limit)
        val tolerance = settings.getInt("tolerance",2)
        val overspeedSignal=settings.getBoolean("overspeed",false) && overspeed.update(speed,limit,tolerance)
        withContext(Dispatchers.Main) {
            if (newCamera && alert != null) {
                if(settings.getBoolean("cameraSound",true)) {
                    val cameraRoadLimit=limit.takeIf { road != null && road.confidence>=.55 && Geo.projection(alert.camera.point,road.road.points).first<=20 }
                    cameraVoice.say(CameraAnnouncement.text(alert.camera,cameraRoadLimit))
                }
                signal(false,settings.getBoolean("vibrate",true))
            } else if(changedLimit != null && settings.getBoolean("limitVoice",true)) {
                cameraVoice.say("Speed limit $changedLimit miles per hour.")
            }
            if(overspeedSignal) signal(true,settings.getBoolean("vibrate",true))
        }
        DriveBus.set(DriveState(true, speed, limit, fix, road, alert, decision,
            cached?.let { wallNow - it.fetchedAt },
            when { speed == null -> "GPS speed unavailable"; cached == null -> "Road and public camera data unavailable"; wallNow-cached.fetchedAt>86_400_000 -> "Using saved road data · offline coverage"; limit == null -> "Road limit unknown"; else -> "" },
            settings.getBoolean("overspeed", false) && overspeed.isOver(speed, limit, tolerance), mapStatus,
            upcoming, publicCameras.size, userCount, importedCount,
            cameras.count { it.source == CameraSource.LUFOP }, turns, fix.accuracyM<=35,section))
        val target = OsmCoverage.refreshTarget(snapshot, fix, wallNow, lastAttempt)
        if (target != null && !fetching) {
            fetching = true; lastAttempt = wallNow; mapStatus = "Fetching road data"
            scope.launch {
                try {
                    val fresh = withContext(Dispatchers.IO) { osm.fetch(target) }
                    previousSnapshot = snapshot
                    snapshot = fresh
                    mapStatus = "Map data ready"
                } catch (error: Exception) {
                    mapStatus = "Map request failed: ${error.message?.take(70) ?: error.javaClass.simpleName}"
                }
                finally { fetching = false }
            }
        }
    }
    private fun cameraEnabled(camera: Camera, settings: android.content.SharedPreferences) = CameraAlertPolicy.enabled(
        camera.type,settings.getBoolean("fixedCamera",true),settings.getBoolean("mobileCamera",true),
        settings.getBoolean("speedCamera",true),settings.getBoolean("redCamera",true))
    private fun signal(sound: Boolean, vibration: Boolean) {
        if (sound) runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).also { tone ->
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
                    Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 450)
                } catch (error: Exception) { tone.release(); throw error }
            }
        }.onFailure { Log.w("SpeedBuddy", "Sound alert unavailable", it) }
        if (vibration) runCatching {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w("SpeedBuddy", "Vibration alert unavailable", it) }
    }
    override fun onDestroy() {
        tick?.cancel(); fixes.close(); scope.cancel(); locationManager.removeUpdates(this); cameraVoice.close()
        scope.coroutineContext[Job]?.invokeOnCompletion { db.close() }
        DriveBus.set(DriveState(status = "Driving mode stopped")); super.onDestroy()
    }
}
