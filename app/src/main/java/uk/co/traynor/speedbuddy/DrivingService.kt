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
    val roadDataStatus: String = "", val limitDecision: LimitDecision? = null,
    val sourceLimitMph: Int? = null, val tooEarlyAvailable: Boolean = false,
    val awaitingBoundary: Boolean = false, val coverageTiles: Int = 0, val targetTiles: Int = 0,
)
object DriveBus { private val mutable = MutableStateFlow(DriveState()); val state = mutable.asStateFlow(); fun set(state: DriveState) { mutable.value = state } }

class DrivingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private lateinit var db: CameraDb
    private lateinit var roads: RoadDb
    private lateinit var downloader: RoadDownload
    private val speedFilter = SpeedFilter(); private val matcher = RoadMatcher()
    private val limitEngine = LimitDecisionEngine()
    private val upcomingDetector = UpcomingLimitDetector()
    private val detector = CameraApproachDetector(); private val overspeed = OverspeedGate()
    private val limits: SpeedLimitProvider = OsmSpeedLimitProvider()
    private val planner = RoadRefreshPlanner()
    private var coverage: Map<RoadTile,Long> = emptyMap()
    private var boundaries: List<BoundaryCorrection> = emptyList()
    private var overrides: List<RoadDb.Override> = emptyList()
    private var ready = false
    private var stopped = false
    private var fetching = false
    private var offline = false
    private var refreshDelayed = false
    private var budgetReached = false
    private var budgetUntil = 0L
    private var updatedAt = 0L
    private var generation = 0L
    private var localGeneration = -1L
    private var localAt = 0L
    private var localPoint: GeoPoint? = null
    private var local = LocalRoads(emptyList(),emptyList())
    private var userCameras = emptyList<Camera>()
    private var imported = emptyList<Camera>()
    private var importedCount = 0
    private var mapStatus = "Loading saved road data"
    private var lastAlertId: String? = null
    private var tick: Job? = null
    private var processing: Job? = null
    private var feedbackJob: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        db = CameraDb(this); roads = RoadDb(this); downloader = RoadDownload(this)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Preserve the old extract as partial coverage. Never claim its 3 km box covers a full tile.
                    if (roads.readableDatabase.rawQuery("SELECT COUNT(*) FROM tiles",null).use { it.moveToFirst();it.getInt(0) } == 0) {
                        LegacyOsmCache(this@DrivingService).cached()?.let { old ->
                            if (old.roads.any { Geo.projection(old.center,it.points).first < 1500 })
                                roads.replace(RoadTileData(RoadTile.at(old.center),old.fetchedAt,old.roads,old.cameras,complete=false))
                        }
                    }
                }
                coverage = withContext(Dispatchers.IO) { roads.coverage() }
                boundaries = withContext(Dispatchers.IO) { roads.boundaries() }
                overrides = withContext(Dispatchers.IO) { roads.overrides() }
                ready = true; mapStatus = "Saved road data ready"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mapStatus = "Local road store unavailable: ${e.message}"
                Log.e("SpeedBuddy","Road cache initialization failed",e)
            }
        }
        val channel = NotificationChannel("drive", "Driving mode", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action in listOf("TOO_EARLY","CHANGED_NOW","CANCEL_BOUNDARY","RESET_CORRECTIONS","SET_OVERRIDE")) {
            if (tick == null || !ready) { if (tick == null) stopSelf(); return START_NOT_STICKY }
            handleFeedback(intent!!)
            return START_NOT_STICKY
        }
        val stop = Intent(this, DrivingService::class.java).setAction("STOP")
        val pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "drive").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Speed Buddy is active").setContentText("GPS speed and camera alerts")
            .addAction(Notification.Action.Builder(null, "Stop", pending).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(42, notification)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DriveBus.set(DriveState(status = "Precise location required")); stopSelf(); return START_NOT_STICKY
        }
        if (tick == null) {
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
            catch (_: Exception) { DriveBus.set(DriveState(status = "GPS unavailable")); stopSelf(); return START_NOT_STICKY }
            tick = scope.launch { while (isActive) { delay(1000); val state = DriveBus.state.value
                if (state.fix != null && SystemClock.elapsedRealtime() - state.fix.elapsedMs > 5000) {
                    speedFilter.current(SystemClock.elapsedRealtime()); matcher.reset(); limitEngine.reset()
                    DriveBus.set(state.copy(speedMph = null, limitMph = null, road = null, alert = null, upcoming = null, tooEarlyAvailable = false, awaitingBoundary = false, status = "GPS signal lost"))
                }
                val current = DriveBus.state.value
                val now = SystemClock.elapsedRealtime()
                DriveBus.set(current.copy(roadDataStatus = roadStatus(now), tooEarlyAvailable = limitEngine.canReport(now)))
                current.fix?.takeIf { now-it.elapsedMs in 0..5000 }?.let { refresh(it) }
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
        val speed = speedFilter.update(fix, now)
        if (!ready) {
            DriveBus.set(DriveBus.state.value.copy(active=true,speedMph=speed,fix=fix,status="Loading saved road data",mapStatus=mapStatus))
            return
        }
        if (processing?.isActive == true) return // Conflate fixes; never queue a growing list of location work.
        processing = scope.launch {
            try {
                val wallNow = System.currentTimeMillis()
                if (localGeneration != generation || now-localAt > 5000 || localPoint?.let { Geo.distance(it,fix.point)>150 } != false) {
                    val result = withContext(Dispatchers.IO) {
                        val nearby = roads.nearby(fix.point)
                        val personal = db.userCameras()
                        val lufop = db.importedNearby(fix.point)
                        val count = db.importedInfo()?.count ?: 0
                        listOf(nearby,personal,lufop,count)
                    }
                    @Suppress("UNCHECKED_CAST")
                    run { local = result[0] as LocalRoads;userCameras = result[1] as List<Camera>;imported = result[2] as List<Camera>;importedCount = result[3] as Int }
                    localPoint=fix.point;localAt=now;localGeneration=generation
                }
                val road = withContext(Dispatchers.Default) { matcher.match(fix,local.roads.map { it.road }) }
                if (SystemClock.elapsedRealtime()-fix.elapsedMs !in 0..5000) { matcher.reset();return@launch }
                val source = limits.limit(road)
                val owner = road?.let { RoadDb.selectOverride(overrides,it.road.id,fix.bearing) }
                val decision = limitEngine.decide(fix,road,source,owner,boundaries,now)
                val limit = decision.mph
                val upcoming = decision.upcoming ?: withContext(Dispatchers.Default) { upcomingDetector.detect(fix,road,limit,local.roads.map { it.road }) }
                val settings = getSharedPreferences("settings",Context.MODE_PRIVATE)
                val publicCameras=local.cameras
                val importedNearby=imported.filterNot { candidate -> publicCameras.any { it.type==candidate.type && Geo.distance(it.point,candidate.point)<25 } }
                val cameras=publicCameras+userCameras+importedNearby
                val enabled=cameras.filter { (it.type==CameraType.SPEED && settings.getBoolean("speedCamera",true)) || (it.type==CameraType.RED_LIGHT && settings.getBoolean("redCamera",true)) }
                val (alert,cameraDecision)=detector.evaluate(fix,road,enabled,speed)
                if(alert!=null && alert.camera.id!=lastAlertId) { lastAlertId=alert.camera.id;signal(settings.getBoolean("cameraSound",true),settings.getBoolean("vibrate",true)) }
                val tolerance=settings.getInt("tolerance",2)
                if(settings.getBoolean("overspeed",false) && overspeed.update(speed,limit,tolerance)) signal(false,settings.getBoolean("vibrate",true))
                val age=local.roads.firstOrNull { it.road.id==road?.road?.id }?.let { (wallNow-it.fetchedAt).coerceAtLeast(0) }
                val wanted=RoadTiles.covering(fix.point)
                DriveBus.set(DriveState(active=true,speedMph=speed,limitMph=limit,fix=fix,road=road,alert=alert,decision=cameraDecision,
                    dataAgeMs=age,status=when { speed==null -> "GPS speed unavailable";limit==null -> "Road limit unknown";else -> "" },
                    overspeed=settings.getBoolean("overspeed",false) && overspeed.isOver(speed,limit,tolerance),mapStatus=mapStatus,
                    upcoming=upcoming,publicCameraCount=publicCameras.size,userCameraCount=userCameras.size,importedCameraCount=importedCount,importedNearbyCount=importedNearby.size,
                    roadDataStatus=roadStatus(now),limitDecision=decision,sourceLimitMph=source,tooEarlyAvailable=limitEngine.canReport(now),awaitingBoundary=limitEngine.pendingFeedback,
                    coverageTiles=wanted.count { it in coverage },targetTiles=wanted.size))
                refresh(fix)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                Log.e("SpeedBuddy","Local road decision failed",e)
                DriveBus.set(DriveBus.state.value.copy(limitMph=null,upcoming=null,status="Road data unavailable",mapStatus="Local decision failure: ${e.message}"))
            }
        }
    }
    private fun roadStatus(now: Long): String = when {
        fetching -> "Updating road data…"
        offline -> if(local.roads.isEmpty()) "Offline • no saved roads here" else "Offline • using saved road data"
        refreshDelayed -> "Using saved road data"
        now-updatedAt in 0..5000 && updatedAt>0 -> "Road data updated"
        budgetReached -> "Saved road data • coverage update paused"
        else -> ""
    }
    private fun refresh(fix: Fix) {
        if(budgetReached && System.currentTimeMillis() >= budgetUntil) budgetReached=false
        if(!ready || fetching || stopped || budgetReached) return
        val target=planner.next(fix,coverage,System.currentTimeMillis()) ?: return
        fetching=true;planner.attempted(System.currentTimeMillis());mapStatus="Updating tile ${target.id}"
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    if(!downloader.permitted()) return@withContext null
                    val fresh=downloader.fetch(target);ensureActive()
                    RoadCacheUpdater(roads).refresh(target) { fresh }
                    val here=RoadTile.at(fix.point)
                    val protected=buildSet { for(y in here.y-1..here.y+1) for(x in here.x-1..here.x+1) add(RoadTile(y,x)) }
                    val before=roads.coverage()
                    roads.cleanup(protected)
                    roads.coverage() to (before.keys-roads.coverage().keys)
                }
                if(result==null) { budgetReached=true;budgetUntil=(System.currentTimeMillis()/86_400_000+1)*86_400_000;mapStatus="Daily public-provider allowance reached; saved data retained" }
                else { coverage=result.first
                    if(result.second.isNotEmpty()) planner.retentionLimited(fix.point)
                    generation++;offline=false;refreshDelayed=false;updatedAt=SystemClock.elapsedRealtime();planner.succeeded();mapStatus="Saved tile ${target.id}; ${coverage.size} tiles available" }
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                offline=e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException
                refreshDelayed=true;planner.failed(System.currentTimeMillis());mapStatus="Refresh failed; retained saved data: ${e.message?.take(100)}"
                Log.w("SpeedBuddy",mapStatus,e)
            } finally { fetching=false }
        }
    }
    private fun handleFeedback(intent: Intent) {
        val state=DriveBus.state.value
        val fix=state.fix ?: return
        val now=SystemClock.elapsedRealtime()
        if(now-fix.elapsedMs !in 0..5000 || feedbackJob?.isActive==true) return
        when(intent.action) {
            "TOO_EARLY" -> if(limitEngine.tooEarly(fix,now)) DriveBus.set(state.copy(limitMph=limitEngine.decide(fix,state.road,state.sourceLimitMph,null,boundaries,now).mph,
                tooEarlyAvailable=false,awaitingBoundary=true,upcoming=state.limitMph?.let { UpcomingLimit(it,0.0,false) }))
            "CANCEL_BOUNDARY" -> { limitEngine.cancelFeedback();DriveBus.set(state.copy(awaitingBoundary=false,tooEarlyAvailable=false)) }
            "CHANGED_NOW" -> {
                val correction=limitEngine.changedNow(fix,now) ?: return
                feedbackJob=scope.launch {
                    try {
                        withContext(Dispatchers.IO) { roads.saveBoundary(correction) }
                        boundaries=withContext(Dispatchers.IO) { roads.boundaries() };limitEngine.feedbackSaved()
                        DriveBus.set(DriveBus.state.value.copy(limitMph=correction.newMph,awaitingBoundary=false,tooEarlyAvailable=false,upcoming=null))
                    } catch(e: Exception) { if(e is CancellationException) throw e;Log.w("SpeedBuddy","Boundary could not be saved",e) }
                }
            }
            "RESET_CORRECTIONS","SET_OVERRIDE" -> {
                if((state.speedMph ?: Double.MAX_VALUE)>=5) return
                val road=state.road?.road
                if(intent.action=="SET_OVERRIDE" && (road==null || fix.bearing==null)) return
                feedbackJob=scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if(intent.action=="RESET_CORRECTIONS") roads.resetCorrections()
                            else roads.setOverride(road!!.id,fix.bearing!!,intent.getIntExtra("mph",0).takeIf { it>0 })
                        }
                        boundaries=withContext(Dispatchers.IO) { roads.boundaries() };overrides=withContext(Dispatchers.IO) { roads.overrides() };limitEngine.reset()
                    } catch(e: Exception) { if(e is CancellationException) throw e;Log.w("SpeedBuddy","Correction update failed",e) }
                }
            }
        }
    }
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
        stopped=true;tick?.cancel();scope.cancel();locationManager.removeUpdates(this)
        // Blocking HTTP/database work may still be unwinding. Close after all children finish.
        CoroutineScope(Dispatchers.IO).launch { scope.coroutineContext[Job]?.join();db.close();roads.close() }
        DriveBus.set(DriveState(status = "Driving mode stopped")); super.onDestroy()
    }
}
