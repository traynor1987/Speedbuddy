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
)
object DriveBus { private val mutable = MutableStateFlow(DriveState()); val state = mutable.asStateFlow(); fun set(state: DriveState) { mutable.value = state } }

class DrivingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private lateinit var db: CameraDb
    private lateinit var osm: OsmDataSource
    private val speedFilter = SpeedFilter(); private val matcher = RoadMatcher()
    private val limitStabilizer = RoadLimitStabilizer()
    private val upcomingDetector = UpcomingLimitDetector()
    private val detector = CameraApproachDetector(); private val overspeed = OverspeedGate()
    private val limits: SpeedLimitProvider = OsmSpeedLimitProvider()
    private var snapshot: OsmSnapshot? = null
    private var previousSnapshot: OsmSnapshot? = null
    private var fetching = false; private var lastAttempt = 0L
    private var mapStatus = "Cached map data"
    private var lastAlertId: String? = null
    private var tick: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        db = CameraDb(this); osm = OsmDataSource(this); snapshot = osm.cached()
        val channel = NotificationChannel("drive", "Driving mode", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
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
                    speedFilter.current(SystemClock.elapsedRealtime()); matcher.reset(); limitStabilizer.reset()
                    DriveBus.set(state.copy(speedMph = null, limitMph = null, road = null, alert = null, status = "GPS signal lost"))
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
        val speed = speedFilter.update(fix, now)
        val wallNow = System.currentTimeMillis()
        val cached = listOfNotNull(snapshot, previousSnapshot)
            .filter { it.usable(fix.point, wallNow) }
            .minByOrNull { Geo.distance(it.center, fix.point) }
        val road = if (cached != null && fix.accuracyM <= 35) matcher.match(fix, cached.roads) else null
        val limit = if (cached != null) limitStabilizer.resolve(fix, road, limits.limit(road), now)
            else { limitStabilizer.reset(); null }
        val upcoming = cached?.let { upcomingDetector.detect(fix, road, limit, it.roads) }
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val publicCameras = listOfNotNull(snapshot, previousSnapshot).filter { it.usable(fix.point, wallNow) }
            .flatMap { it.cameras }.distinctBy { it.id }
        val userCameras = db.userCameras()
        val imported = db.importedNearby(fix.point).filterNot { candidate ->
            publicCameras.any { it.type == candidate.type && Geo.distance(it.point, candidate.point) < 25 }
        }
        val cameras = publicCameras + userCameras + imported
        val enabled = cameras.filter { (it.type == CameraType.SPEED && settings.getBoolean("speedCamera", true)) ||
            (it.type == CameraType.RED_LIGHT && settings.getBoolean("redCamera", true)) }
        val (alert, decision) = detector.evaluate(fix, road, enabled, speed)
        if (alert != null && alert.camera.id != lastAlertId) {
            lastAlertId = alert.camera.id; signal(settings.getBoolean("cameraSound", true), settings.getBoolean("vibrate", true))
        }
        val tolerance = settings.getInt("tolerance", 2)
        if (settings.getBoolean("overspeed", false) && overspeed.update(speed, limit, tolerance)) signal(false, settings.getBoolean("vibrate", true))
        DriveBus.set(DriveState(true, speed, limit, fix, road, alert, decision,
            cached?.let { wallNow - it.fetchedAt },
            when { speed == null -> "GPS speed unavailable"; cached == null -> "Road and public camera data unavailable"; limit == null -> "Road limit unknown"; else -> "" },
            settings.getBoolean("overspeed", false) && overspeed.isOver(speed, limit, tolerance), mapStatus,
            upcoming, publicCameras.size, userCameras.size, db.importedInfo()?.count ?: 0, imported.size))
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
        tick?.cancel(); scope.cancel(); locationManager.removeUpdates(this); db.close()
        DriveBus.set(DriveState(status = "Driving mode stopped")); super.onDestroy()
    }
}
