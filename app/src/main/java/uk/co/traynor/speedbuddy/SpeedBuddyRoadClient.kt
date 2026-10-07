package uk.co.traynor.speedbuddy

import java.net.HttpURLConnection
import java.net.URL

/** Authenticated regional fallback. It never sends a synthetic heading or a token in a URL. */
internal class SpeedBuddyRoadClient(private val credential: () -> String?, private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun request(fix: Fix, previousWayId: Long? = null): LiveRoadState {
        require(fix.bearing != null && fix.bearing in 0.0..<360.0) { "Reliable heading required" }
        require(fix.accuracyM in 1.0..100.0) { "Invalid location accuracy" }
        val token=credential()?.takeIf(String::isNotBlank) ?: return LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,false)
        val query=listOf("lat=${fix.point.lat}","lon=${fix.point.lon}","heading=${fix.bearing}","accuracyMetres=${fix.accuracyM}")+
            listOfNotNull(previousWayId?.let { "previousWayId=$it" },fix.speedMps?.let { "speedMps=$it" })
        val c=open(URL("https://api.jtwebsolutions.co.uk/speedbuddy/v1/road-state?${query.joinToString("&")}"))
        try { c.requestMethod="GET";c.connectTimeout=3000;c.readTimeout=5000;c.setRequestProperty("Authorization","Bearer $token")
            val code=c.responseCode
            if(code==503) return LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true)
            if(code !in 200..299) throw IllegalStateException("Road API HTTP $code")
            return c.inputStream.bufferedReader().use { LiveRoadStateParser.parse(it.readText()) }
        } finally { c.disconnect() }
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
        speedBuddyRoadClient = SpeedBuddyRoadClient { getSharedPreferences("settings", Context.MODE_PRIVATE).getString("speedBuddyCredential", null) }
        roadRepository=DrivingRoadRepository(roads,OsmDataSource(this)::cachedRegional)
        cameraVoice = CameraVoice(this) { signal(true,false) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Preserve the old extract as partial coverage. Never claim its 3 km box covers a full tile.
                    if (roads.readableDatabase.rawQuery("SELECT COUNT(*) FROM tiles",null).use { it.moveToFirst();it.getInt(0) } == 0) {
