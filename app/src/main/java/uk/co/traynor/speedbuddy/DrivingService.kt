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
    val turns: List<TurnLimit> = emptyList(), val alertPositionFresh: Boolean = true,
    val averageSection: ActiveAverageSection? = null,
    val roadCacheRevision: Long = 0L,val boundaryAvailable: Boolean = false,val correctionMessage: String = "",
    val roadRequestKind: String = "No request",val subdivisionLevel: Int? = null,val currentRegionStatus: String = "Idle",val completedRoadRegions: Int = 0,
)
object DriveBus {
    private val mutable = MutableStateFlow(DriveState())
    val state = mutable.asStateFlow()
    fun set(state: DriveState) { mutable.value = state }

    /** Location ownership remains in [DrivingService]; projection consumers get every useful speed promptly. */
    fun publishLocationSpeed(speedMph: Double?, fix: Fix) {
        mutable.value = mutable.value.copy(active = true, speedMph = speedMph, fix = fix)
    }
}

class DrivingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private lateinit var db: CameraDb
    private lateinit var roads: RoadDb
    private lateinit var downloader: RoadDownload
    private lateinit var adaptiveDownloader: AdaptiveRoadDownloader
    private lateinit var roadRepository: DrivingRoadRepository
    private lateinit var regionalMatcher: RegionalPackMatcher
    /** 0.4.0's fallback client is deliberately owned by this existing service, never by Auto. */
    private lateinit var speedBuddyRoadClient: SpeedBuddyRoadClient
    private val speedFilter = SpeedFilter()
    private val limitPipeline=DrivingLimitPipeline()
    private val matcher get()=limitPipeline.matcher
    private val limitEngine get()=limitPipeline.engine
    private lateinit var cameraVoice: CameraVoice
    private val limitVoiceGate = DeferredLimitVoice()
    private val sectionTracker = AverageSectionTracker()
    private val turnDetector = TurnLimitDetector()
    private var cameraRevision = -1L
    private var roadRevision = -1L
    private var mapCorrections: Map<String,RoadLimitCorrection> = emptyMap()
    private var effectiveCameras = emptyList<Camera>()
    private var lastMobilePrune = 0L
    private val detector = CameraApproachDetector(); private val overspeed = OverspeedGate()
    private val planner = RoadRefreshPlanner()
    private var coverage: Map<RoadTile,Long> = emptyMap()
    private var boundaries: List<BoundaryCorrection> = emptyList()
    private var overrides: List<RoadDb.Override> = emptyList()
    private var observations: List<BoundaryObservation> = emptyList()
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
    private var regional = RegionalPackMatcher.Result(RoadProviderState.COVERAGE_UNAVAILABLE, null)
    private var userCameras = emptyList<Camera>()
    private var imported = emptyList<Camera>()
    private var importedCount = 0
    private var mapStatus = "Loading saved road data"
    private var roadRequestKind = "No request"
    private var roadSubdivisionLevel: Int? = null
    private var currentRegionStatus = "Idle"
    private var lastAlertId: String? = null
    private var tick: Job? = null
    private var processing: Job? = null
    private var feedbackJob: Job? = null
    private var feedbackMessage = ""
    private var feedbackAt = 0L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        db = CameraDb(this); roads = RoadDb(this); downloader = RoadDownload(this); adaptiveDownloader=AdaptiveRoadDownloader(roads,downloader)
        speedBuddyRoadClient = SpeedBuddyRoadClient(credential = { getSharedPreferences("settings", Context.MODE_PRIVATE).getString("speedBuddyCredential", null) })
        roadRepository=DrivingRoadRepository(roads,OsmDataSource(this)::cachedRegional)
        regionalMatcher=RegionalPackMatcher(this)
        cameraVoice = CameraVoice(this) { signal(true,false) }
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
                observations=withContext(Dispatchers.IO) { roads.observations() }
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
        if (intent?.action in listOf("TOO_EARLY","CHANGED_NOW","STARTS_HERE","CANCEL_BOUNDARY","RESET_CORRECTIONS","RESET_ROAD","SET_LIMIT","SET_OVERRIDE")) {
            if (tick == null || !ready) { if (tick == null) stopSelf(); return START_NOT_STICKY }
            handleFeedback(intent!!)
            return START_NOT_STICKY
        }
        val stop = Intent(this, DrivingService::class.java).setAction("STOP")
        val pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "drive").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setContentTitle("Speed Buddy is active").setContentText("GPS speed and camera alerts")
            .addAction(Notification.Action.Builder(null, "Stop", pending).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(42, notification)
        } catch (_: RuntimeException) {
            DriveBus.set(DriveState(status="Driving mode needs location permission while the app is open"))
            stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DriveBus.set(DriveState(status = "Precise location required")); stopSelf(); return START_NOT_STICKY
        }
        if (tick == null) {
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()) }
            catch (_: Exception) { DriveBus.set(DriveState(status = "GPS unavailable")); stopSelf(); return START_NOT_STICKY }
            tick = scope.launch { while (isActive) { delay(1000); val state = DriveBus.state.value
                if (state.fix != null && SystemClock.elapsedRealtime() - state.fix.elapsedMs > 5000) {
                    speedFilter.current(SystemClock.elapsedRealtime()); matcher.reset(); limitEngine.reset()
                    DriveBus.set(state.copy(speedMph = null, limitMph = null, road = null, alert = null, upcoming = null, limitDecision=null, turns=emptyList(), alertPositionFresh=false, averageSection=state.averageSection?.copy(remainingM=Double.NaN), tooEarlyAvailable = false, boundaryAvailable=false, awaitingBoundary = false, status = "GPS signal lost"))
                    if(state.status!="GPS signal lost") {
                        val receipt=LimitDiagnostics.snapshot("GPS signal lost",DriveBus.state.value)
                        withContext(Dispatchers.IO) { runCatching { roads.recordDiagnostic(receipt) }
                            .onFailure { Log.w("SpeedBuddy","GPS diagnostic could not be saved",it) } }
                    }
                }
                val settings=getSharedPreferences("settings",Context.MODE_PRIVATE)
                val wallNow=System.currentTimeMillis()
                if(wallNow-lastMobilePrune>=60_000) {
                    withContext(Dispatchers.IO) { MobileReportStore(db).prune(wallNow) };lastMobilePrune=wallNow
                }
                val visible=DriveBus.state.value
                visible.alert?.let { alert ->
                    val present=if(alert.camera.type==CameraType.MOBILE) withContext(Dispatchers.IO) {
                        val report=alert.camera.mobileReport!!
                        MobileReportStore(db).activeInBounds(report.point.lat-.00001,report.point.lon-.00001,
                            report.point.lat+.00001,report.point.lon+.00001,wallNow).any { it.id==report.id }
                    } else true
                    if(!present || !cameraEnabled(alert.camera,settings)) DriveBus.set(DriveBus.state.value.copy(
                        alert=null,decision=CameraDecision(null,null,false,"Camera warning ended")))
                }
                if(!fixedSpeedEnabled(settings) && DriveBus.state.value.averageSection!=null)
                    DriveBus.set(DriveBus.state.value.copy(averageSection=null))
                cameraVoice.revalidate();announceLimitIfReady(settings)
                val current = DriveBus.state.value
                val now = SystemClock.elapsedRealtime()
                DriveBus.set(current.copy(roadDataStatus = roadStatus(now), tooEarlyAvailable = limitEngine.canReport(now),boundaryAvailable=limitEngine.canMarkBoundary(now),
                    correctionMessage=feedbackMessage.takeIf { now-feedbackAt in 0..6000 } ?: ""))
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
        // Road matching is deliberately conflated, but the authoritative GPS speed is not.
        // This is the same phone-owned fix that the phone UI receives; car projection never owns GPS.
        DriveBus.publishLocationSpeed(speed, fix)
        if (processing?.isActive == true) return // Conflate fixes; never queue a growing list of location work.
        processing = scope.launch {
            try {
                val wallNow = System.currentTimeMillis()
                if (localGeneration != generation || cameraRevision!=OwnerDataRevision.cameras || now-localAt > 5000 || localPoint?.let { Geo.distance(it,fix.point)>150 } != false) {
                    val result = withContext(Dispatchers.IO) {
                        val nearby=roadRepository.nearby(fix.point,RoadTile.at(fix.point) in coverage)
                        val regionalResult=regionalMatcher.match(fix)
                        val personal = db.userCameras()
                        val lufop = db.importedNearby(fix.point)
                        val count = db.importedInfo()?.count ?: 0
                        val effective=db.effectiveInBounds(fix.point.lat-.015,fix.point.lon-.025,
                            fix.point.lat+.015,fix.point.lon+.025,nearby.cameras)
                        listOf(nearby,personal,lufop,count,effective,regionalResult)
                    }
                    @Suppress("UNCHECKED_CAST")
                    run { local = result[0] as LocalRoads;userCameras = result[1] as List<Camera>;imported = result[2] as List<Camera>;importedCount = result[3] as Int;effectiveCameras=result[4] as List<Camera>;regional=result[5] as RegionalPackMatcher.Result }
                    localPoint=fix.point;localAt=now;localGeneration=generation;cameraRevision=OwnerDataRevision.cameras
                }
                if(roadRevision!=OwnerDataRevision.roads) {
                    val ownerData=withContext(Dispatchers.IO) { Triple(db.roadCorrections().associateBy { it.id },roads.overrides(),roads.boundaries()) }
                    mapCorrections=ownerData.first;overrides=ownerData.second;boundaries=ownerData.third
                    observations=withContext(Dispatchers.IO) { roads.observations() }
                    if(roadRevision>=0) limitEngine.reset()
                    roadRevision=OwnerDataRevision.roads
                }
                val correctedRoads=local.roads.map { mapCorrections[it.road.id]?.apply(it.road) ?: it.road }
                val decisionAt=SystemClock.elapsedRealtime()
                if (decisionAt-fix.elapsedMs !in 0..5000) return@launch
                // State transitions and picker plans share the service's main-thread owner.
                val result=limitPipeline.evaluate(fix,local.roads.map { it.road },overrides,mapCorrections,boundaries,observations,decisionAt,wallNow,regional)
                val road=result.road;val source=result.source;val decision=result.decision;val limit=decision.mph
                val upcoming=result.upcoming
                val settings = getSharedPreferences("settings",Context.MODE_PRIVATE)
                val publicCameras=local.cameras
                val importedNearby=imported.filterNot { candidate -> publicCameras.any { it.type==candidate.type && Geo.distance(it.point,candidate.point)<25 } }
                val enabled=effectiveCameras.filter { cameraEnabled(it,settings) }
                val tolerance=settings.getInt("tolerance",2)
                val alertLimit=limit.takeUnless { decision.assumed }
                val (alert,cameraDecision)=detector.evaluate(fix,road,enabled,speed,correctedRoads,wallNow,
                    matchedRoadLimitMph=alertLimit,toleranceMph=tolerance)
                val section=if(fixedSpeedEnabled(settings)) sectionTracker.update(fix,road,local.averageSections) else null
                val turns=turnDetector.detect(fix,road,limit,correctedRoads)
                val overspeedSignal=settings.getBoolean("overspeed",false) && overspeed.update(speed,alertLimit,tolerance)
                val age=local.roads.firstOrNull { it.road.id==road?.road?.id }?.let { (wallNow-it.fetchedAt).coerceAtLeast(0) }
                val wanted=RoadTiles.covering(fix.point)
                DriveBus.set(DriveState(active=true,speedMph=speed,limitMph=limit,fix=fix,road=road,alert=alert,decision=cameraDecision,
                    dataAgeMs=age,status=when { speed==null -> "GPS speed unavailable";limit==null -> "Road limit unknown";else -> "" },
                    overspeed=settings.getBoolean("overspeed",false) && overspeed.isOver(speed,alertLimit,tolerance),mapStatus=mapStatus,
                    upcoming=upcoming,publicCameraCount=publicCameras.size,userCameraCount=userCameras.size,importedCameraCount=importedCount,importedNearbyCount=importedNearby.size,
                    turns=turns,alertPositionFresh=fix.accuracyM<=35,averageSection=section,roadDataStatus=roadStatus(now),limitDecision=decision,sourceLimitMph=source,tooEarlyAvailable=limitEngine.canReport(now),awaitingBoundary=limitEngine.pendingFeedback,
                    coverageTiles=wanted.count { it in coverage },targetTiles=wanted.size,roadCacheRevision=generation,boundaryAvailable=limitEngine.canMarkBoundary(now),
                    correctionMessage=feedbackMessage.takeIf { now-feedbackAt in 0..6000 } ?: "",
                    roadRequestKind=roadRequestKind,subdivisionLevel=roadSubdivisionLevel,currentRegionStatus=currentRegionStatus,completedRoadRegions=coverage.size).let(result::applyTo))
                val diagnostic=LimitDiagnostics.snapshot("decision",DriveBus.state.value,transition=limitEngine.transitionEvidence())
                withContext(Dispatchers.IO) { runCatching { roads.recordDiagnostic(diagnostic) }.onFailure { Log.w("SpeedBuddy","Decision diagnostic could not be saved",it) } }
                cameraVoice.revalidate()
                val warning=alert?.warning
                if(alert!=null && warning!=null) {
                    cameraVoice.play(CameraAudioCue.from(alert.camera,warning,settings.getBoolean("cameraSound",true)),
                        relevant={ cameraCueRelevant(CameraEncounters.key(alert.camera),false,warning.limitMph) },
                        voiceAllowed={ settings.getBoolean("cameraSound",true) && currentCameraLimit()==warning.limitMph &&
                            (!warning.speeding || cameraCueRelevant(CameraEncounters.key(alert.camera),true,warning.limitMph)) })
                    signal(false,settings.getBoolean("vibrate",true))
                }
                announceLimitIfReady(settings)
                val cameraLimit=alert?.let { CameraLimits.resolve(it.camera,road,alertLimit) }
                val cameraCoversSpeeding=cameraLimit!=null && speed!=null && speed>cameraLimit+tolerance.coerceAtLeast(0)
                if(overspeedSignal && !cameraCoversSpeeding && warning?.doubleBeep!=true) signal(true,settings.getBoolean("vibrate",true))
                refresh(fix)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                Log.e("SpeedBuddy","Local road decision failed",e)
                // Lookup failures use the same bounded geometry/heading continuity as
                // an empty match; never publish Unknown merely as the catch default.
                val fallback=runCatching {
                    limitPipeline.evaluate(fix,emptyList(),overrides,mapCorrections,boundaries,observations,
                        SystemClock.elapsedRealtime(),System.currentTimeMillis())
                }.getOrElse {
                    DriveLimitResult(fix,null,null,LimitDecision(null,reason="Unavailable: local decision failure; continuity could not be evaluated"),null)
                }.let { it.copy(decision=it.decision.copy(reason=it.decision.reason+"; local road lookup failed")) }
                DriveBus.set(fallback.applyTo(DriveBus.state.value).copy(speedMph=speed,alert=null,
                    decision=CameraDecision(null,null,false,"Local road lookup failed"),
                    overspeed=false,alertPositionFresh=false,turns=emptyList(),mapStatus="Local decision failure: ${e.message}"))
                val receipt=LimitDiagnostics.snapshot("lookup failure",DriveBus.state.value,transition=limitEngine.transitionEvidence())
                withContext(Dispatchers.IO) { runCatching { roads.recordDiagnostic(receipt) }
                    .onFailure { Log.w("SpeedBuddy","Failure diagnostic could not be saved",it) } }
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
        roadRequestKind="Normal tile";roadSubdivisionLevel=target.level;currentRegionStatus="Downloading"
        DriveBus.set(DriveBus.state.value.copy(roadRequestKind=roadRequestKind,subdivisionLevel=roadSubdivisionLevel,currentRegionStatus=currentRegionStatus))
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    if(!downloader.permitted()) return@withContext null
                    val before=roads.coverage()
                    // The current tile gets its exact current-position child first. A
                    // small ahead prefetch has no current point inside it, so use its
                    // centre rather than accidentally subdividing the wrong quadrant.
                    val priorityPoint=fix.point.takeIf(target::contains) ?: target.center
                    val fetched=adaptiveDownloader.fetch(target,priorityPoint);ensureActive()
                    RoadCacheUpdater(roads).store(fetched.data)
                    val here=RoadTile.at(fix.point)
                    val protected=buildSet { for(y in here.y-1..here.y+1) for(x in here.x-1..here.x+1) add(RoadTile(y,x)) }
                    roads.cleanup(protected)
                    Triple(roads.coverage(),before.keys-roads.coverage().keys,fetched)
                }
                if(result==null) { budgetReached=true;budgetUntil=(System.currentTimeMillis()/86_400_000+1)*86_400_000;mapStatus="Daily public-provider allowance reached; saved data retained" }
                else { coverage=result.first
                    if(result.second.isNotEmpty()) planner.retentionLimited(fix.point)
                    generation++;offline=false;refreshDelayed=false;updatedAt=SystemClock.elapsedRealtime();planner.succeeded()
                    val detail=if(result.third.subdivisionLevel>0) "subdivided child ${result.third.requested.id} (level ${result.third.subdivisionLevel})" else "normal tile ${target.id}"
                    mapStatus="Saved $detail; ${coverage.size} completed regions available"
                    roadRequestKind=if(result.third.subdivisionLevel>0) "Subdivided child" else "Normal tile";roadSubdivisionLevel=result.third.subdivisionLevel;currentRegionStatus="Complete"
                    DriveBus.set(DriveBus.state.value.copy(roadRequestKind=roadRequestKind,subdivisionLevel=roadSubdivisionLevel,currentRegionStatus=currentRegionStatus,completedRoadRegions=coverage.size,mapStatus=mapStatus)) }
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                offline=e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException
                refreshDelayed=true;planner.failed(System.currentTimeMillis());mapStatus="Refresh failed; retained saved data: ${e.message?.take(100)}"
                currentRegionStatus="Failed"
                DriveBus.set(DriveBus.state.value.copy(currentRegionStatus=currentRegionStatus,mapStatus=mapStatus))
                Log.w("SpeedBuddy",mapStatus,e)
            } finally { fetching=false }
        }
    }
    private fun handleFeedback(intent: Intent) {
        val state=DriveBus.state.value
        val fix=state.fix ?: return
        val now=SystemClock.elapsedRealtime()
        if(now-fix.elapsedMs !in 0..5000 || feedbackJob?.isActive==true) return
        if(intent.action in listOf("SET_LIMIT","STARTS_HERE","TOO_EARLY") &&
            intent.getStringExtra("road")!=state.road?.road?.id) { feedback("Road changed. Tap the sign again.");return }
        when(intent.action) {
            "TOO_EARLY" -> if(limitEngine.tooEarly(fix,now)) {
                val d=limitEngine.decide(fix,state.road,state.sourceLimitMph,null,boundaries,now)
                DriveBus.set(state.copy(limitMph=d.mph,limitDecision=d,tooEarlyAvailable=false,awaitingBoundary=true,upcoming=d.upcoming))
            }
            "CANCEL_BOUNDARY" -> { limitEngine.cancelFeedback();DriveBus.set(state.copy(awaitingBoundary=false,tooEarlyAvailable=false)) }
            "CHANGED_NOW","STARTS_HERE" -> {
                if(intent.getStringExtra("road")?.let { it!=state.road?.road?.id } == true) { feedback("Road changed. Tap the sign again.");return }
                if(intent.action=="STARTS_HERE" && intent.getIntExtra("mph",OWNER_UNKNOWN)!=state.sourceLimitMph) {
                    feedback("Limit changed. Tap the sign again.");return
                }
                val correction=limitEngine.startsHere(fix,now) ?: run { feedback("Wait for a clear road and GPS match.");return }
                feedbackJob=scope.launch {
                    try {
                        withContext(Dispatchers.IO) { roads.saveBoundary(correction) }
                        boundaries=withContext(Dispatchers.IO) { roads.boundaries() };limitEngine.feedbackSaved()
                        applyLiveCorrections()
                        feedback("Limit starts here • saved")
                    } catch(e: Exception) { if(e is CancellationException) throw e;Log.w("SpeedBuddy","Boundary could not be saved",e);feedback("Could not save. Try again when stopped.") }
                }
            }
            "SET_LIMIT","RESET_CORRECTIONS","RESET_ROAD","SET_OVERRIDE" -> {
                if(intent.action!="SET_LIMIT" && (state.speedMph ?: Double.MAX_VALUE)>=5) return
                val road=state.road?.road
                if(intent.action!="RESET_CORRECTIONS" && (road==null || fix.bearing==null)) {
                    val receipt=LimitDiagnostics.snapshot("unmatched correction",state,intent.getIntExtra("mph",OWNER_UNKNOWN),transition=limitEngine.transitionEvidence())
                    scope.launch(Dispatchers.IO) { runCatching { roads.recordDiagnostic(receipt) } }
                    feedback(unmatchedCorrectionMessage())
                    return
                }
                val selected=intent.getIntExtra("mph",OWNER_UNKNOWN)
                val plan=if(intent.action in listOf("SET_LIMIT","SET_OVERRIDE")) limitEngine.planSelection(fix,state.road,state.sourceLimitMph,
                    selected,intent.getStringExtra("road") ?: road!!.id,now,System.currentTimeMillis(),observations) else null
                if(intent.action in listOf("SET_LIMIT","SET_OVERRIDE") && plan==null) {
                    val receipt=LimitDiagnostics.snapshot("rejected correction",state,selected,transition=limitEngine.transitionEvidence())
                    scope.launch(Dispatchers.IO) { runCatching { roads.recordDiagnostic(receipt) } }
                    feedback("Road or GPS changed. Tap the sign again.");return
                }
                val receipt=LimitDiagnostics.snapshot("correction",state,selected,plan,limitEngine.transitionEvidence())
                feedbackJob=scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if(intent.action=="RESET_CORRECTIONS") roads.resetCorrections()
                            else if(intent.action=="RESET_ROAD") roads.setOverride(road!!.id,fix.bearing!!,null)
                            else roads.saveSelection(plan!!,receipt)
                        }
                        boundaries=withContext(Dispatchers.IO) { roads.boundaries() };overrides=withContext(Dispatchers.IO) { roads.overrides() }
                        observations=withContext(Dispatchers.IO) { roads.observations() }
                        limitPipeline.acceptSavedSelection(plan)
                        // Never overwrite a newer road fix with the saved picker target.
                        applyLiveCorrections()
                        feedback(plan?.message ?: "Corrections reset")
                    } catch(e: Exception) { if(e is CancellationException) throw e;Log.w("SpeedBuddy","Correction update failed",e);feedback("Could not save. Try again when stopped.") }
                }
            }
        }
    }
    private fun feedback(message: String) {
        feedbackMessage=message;feedbackAt=SystemClock.elapsedRealtime()
        DriveBus.set(DriveBus.state.value.copy(correctionMessage=message))
    }
    private fun applyLiveCorrections() {
        val live=DriveBus.state.value
        val current=live.fix
        val now=SystemClock.elapsedRealtime()
        if(current==null || now-current.elapsedMs !in 0..5000) {
            DriveBus.set(live.copy(limitMph=null,limitDecision=null,upcoming=null,awaitingBoundary=false,boundaryAvailable=false,tooEarlyAvailable=false))
            return
        }
        val result=limitPipeline.evaluate(current,local.roads.map { it.road },overrides,mapCorrections,boundaries,observations,now,System.currentTimeMillis(),regional)
        DriveBus.set(result.applyTo(live).copy(awaitingBoundary=false,boundaryAvailable=false,tooEarlyAvailable=false))
    }
    private fun fixedSpeedEnabled(settings: android.content.SharedPreferences) = CameraAlertPolicy.enabled(
        CameraType.AVERAGE,settings.getBoolean("fixedCamera",true),settings.getBoolean("mobileCamera",true),
        settings.getBoolean("speedCamera",true),settings.getBoolean("redCamera",true))
    private fun cameraCueRelevant(id: String, speeding: Boolean, limit: Int?): Boolean {
        val live = DriveBus.state.value
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val fresh = live.active && live.alertPositionFresh && live.fix?.let {
            SystemClock.elapsedRealtime() - it.elapsedMs in 0..5_000
        } == true
        return CameraCueValidity.relevant(live.alert, live.speedMph, fresh,
            live.alert?.let { cameraEnabled(it.camera, settings) } == true,
            id, speeding, limit, settings.getInt("tolerance", 2), System.currentTimeMillis(),
            currentLimit = currentCameraLimit())
    }
    private fun currentCameraLimit(): Int? {
        val live = DriveBus.state.value
        return live.alert?.let { CameraLimits.resolve(it.camera, live.road, live.limitMph.takeUnless { live.limitDecision?.assumed==true }) }
    }
    private fun announceLimitIfReady(settings: android.content.SharedPreferences) {
        val current = DriveBus.state.value
        val limit = limitVoiceGate.update(current.limitMph.takeUnless { current.limitDecision?.assumed==true }, cameraVoice.busy, settings.getBoolean("limitVoice", true))
        if (limit != null) cameraVoice.play(CameraAudioCue("Speed limit $limit miles per hour.", false),
            relevant = { DriveBus.state.value.active && DriveBus.state.value.limitMph == limit && DriveBus.state.value.limitDecision?.assumed!=true },
            voiceAllowed = { settings.getBoolean("limitVoice", true) })
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
        stopped=true;tick?.cancel();cameraVoice.close();scope.cancel();locationManager.removeUpdates(this)
        // Blocking HTTP/database work may still be unwinding. Close after all children finish.
        CoroutineScope(Dispatchers.IO).launch { scope.coroutineContext[Job]?.join();db.close();roads.close() }
        DriveBus.set(DriveState(status = "Driving mode stopped")); super.onDestroy()
    }
}
