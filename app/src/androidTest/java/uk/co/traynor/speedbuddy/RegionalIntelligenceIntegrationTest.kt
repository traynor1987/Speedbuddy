package uk.co.traynor.speedbuddy

import android.content.ContextWrapper
import android.location.Location
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Schema-v1 synthetic packs; exercises production RTree, service and camera/decision paths. */
class RegionalIntelligenceIntegrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val p=GeoPoint(53.5,-2.8)
    private var service: DrivingService?=null
    private val cameraIds=mutableListOf<String>()
    @Before fun before() { RegionalPackStore(context).delete("lancashire");RegionalPackStore(context).delete("merseyside") }
    @After fun after() {
        service?.let { instrumentation.runOnMainSync { it.onDestroy() } }
        CameraDb(context).use { db -> cameraIds.forEach(db::delete) }
        before()
    }
    private fun road(id: Int,points: List<GeoPoint>,tags: Map<String,String>)=Road("way/$id",tags["name"],points,tags)
    private fun tags(mph: String="30 mph")=mapOf("highway" to "residential","name" to "Test Road","maxspeed" to mph)
    private fun install(roads: List<Road>,region: String="lancashire",malformed: Boolean=false,
        coverage: String="[[[[-3.0,53.0],[-2.0,53.0],[-2.0,54.0],[-3.0,54.0],[-3.0,53.0]]]]"): File {
        val version="cycle2-${java.util.UUID.randomUUID()}"
        val raw=File(context.cacheDir,"$version.sqlite")
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            for((k,v) in mapOf("formatVersion" to "1","matcherVersion" to "1","dataset" to "{\"region\":\"$region\"}",
                "coverage" to coverage))
                db.execSQL("INSERT INTO metadata VALUES(?,?)",arrayOf(k,v))
            db.execSQL("CREATE TABLE roads(osm_way_id INTEGER PRIMARY KEY,coordinates TEXT NOT NULL,tags TEXT NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE roads_rtree USING rtree(osm_way_id,min_lon,max_lon,min_lat,max_lat)")
            for(r in roads) {
                val id=r.id.substringAfter('/').toLong()
                db.execSQL("INSERT INTO roads VALUES(?,?,?)",arrayOf<Any>(id,
                    if(malformed) "not-json" else JSONArray(r.points.map { listOf(it.lon,it.lat) }).toString(),JSONObject(r.tags).toString()))
                db.execSQL("INSERT INTO roads_rtree VALUES(?,?,?,?,?)",arrayOf<Any>(id,r.points.minOf { it.lon },r.points.maxOf { it.lon },r.points.minOf { it.lat },r.points.maxOf { it.lat }))
            }
        }
        fun digest(f: File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)}
        val gzip=File(context.cacheDir,"$version.gz")
        GZIPOutputStream(gzip.outputStream()).use { out -> raw.inputStream().use { it.copyTo(out) } }
        val d=RegionalPackDescriptor(region,region,version,1,"2026-10-08T00:00:00Z",gzip.length(),raw.length(),digest(gzip),digest(raw),
            "https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$region/$version/roads.sqlite.gz","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$region/$version/manifest.json")
        return RegionalPackStore(context).install(d,gzip).database
    }
    private fun fix(point: GeoPoint=p,bearing: Double?=0.0,t: Long=1000)=Fix(point,5.0,10.0,1.0,bearing,t)
    private fun evaluate(matcher: RegionalPackMatcher,pipeline: DrivingLimitPipeline,f: Fix,legacy: List<Road> = emptyList(),owners: List<RoadDb.Override> = emptyList())=
        pipeline.evaluate(f,legacy,owners,emptyMap(),emptyList(),emptyList(),f.elapsedMs,10_000+f.elapsedMs,matcher.match(f))
    @Test fun realPackProvidesForwardContextBeyondThePointMatchingWindow() {
        val j=Geo.ahead(p,0.0,120.0)
        val current=road(201,listOf(Geo.ahead(p,180.0,200.0),j),tags())
        val next=road(202,listOf(j,Geo.ahead(j,0.0,300.0)),tags("50 mph"))
        install(listOf(current,next))
        val m=RegionalPackMatcher(context);val r=m.match(fix())
        assertEquals("way/201",r.match!!.road.id)
        assertTrue(r.candidates.any { it.id=="way/202" })
        assertEquals(50,evaluate(m,DrivingLimitPipeline(),fix()).upcoming?.mph)
    }
    @Test fun manyNearbyBoundingBoxesDoNotHideAnUnambiguousPointMatch() {
        val current=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags())
        // Dense roads 35-55 metres away are context, not point-match contenders.
        val others=(1..90).map { i ->
            val q=Geo.ahead(p,90.0,35.0+i*.2)
            road(300+i,listOf(Geo.ahead(q,180.0,50.0),Geo.ahead(q,0.0,50.0)),tags("20 mph"))
        }
        install(listOf(current)+others)
        assertEquals("way/201",RegionalPackMatcher(context).match(fix()).match?.road?.id)
    }
    @Test fun corruptPackCannotSuppressAHealthyOverlappingRegion() {
        val r=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags())
        val broken=install(listOf(r));broken.writeText("corrupt SQLite")
        install(listOf(r),"merseyside")
        val result=RegionalPackMatcher(context).match(fix())
        assertEquals(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,result.state)
        assertEquals("way/201",result.match?.road?.id)
    }
    @Test fun failedCoveringReaderCannotClaimCompleteCarriagewayContext() {
        val r=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags())
        val broken=install(listOf(r))
        SQLiteDatabase.openDatabase(broken.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE roads_rtree") }
        install(listOf(r),"merseyside")
        assertFalse(RegionalPackMatcher(context).match(fix()).contextComplete)
    }
    @Test fun conflictingSourceNationalLimitsInOverlappingPacksRemainUncertain() {
        val r=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags()-"maxspeed"+mapOf("source:maxspeed" to "GB:nsl_single"))
        install(listOf(r));install(listOf(r.copy(tags=r.tags+mapOf("source:maxspeed" to "GB:nsl_dual"))),"merseyside")
        val result=RegionalPackMatcher(context).match(fix())
        assertEquals(RoadProviderState.ROAD_MATCH_UNCERTAIN,result.state);assertNull(result.match)
    }
    @Test fun contextTruncationCannotPretendParallelRoadEvidenceIsComplete() {
        val current=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags())
        val many=(1..520).map { i ->
            val q=Geo.ahead(p,90.0,80.0+i*.2)
            road(300+i,listOf(Geo.ahead(q,180.0,20.0),Geo.ahead(q,0.0,20.0)),tags())
        }
        install(listOf(current)+many)
        val result=RegionalPackMatcher(context).match(fix())
        assertEquals("way/201",result.match?.road?.id);assertFalse(result.contextComplete)
    }
    @Test fun neighboringInstalledPackProvidesLookAheadOutsideItsPointCoverage() {
        val j=Geo.ahead(p,0.0,120.0)
        val current=road(201,listOf(Geo.ahead(p,180.0,200.0),j),tags())
        val next=road(202,listOf(j,Geo.ahead(j,0.0,300.0)),tags("50 mph"))
        install(listOf(current))
        val south=Geo.ahead(p,0.0,60.0).lat
        install(listOf(next),"merseyside",coverage="[[[[-3.0,$south],[-2.0,$south],[-2.0,54.0],[-3.0,54.0],[-3.0,$south]]]]")
        val matcher=RegionalPackMatcher(context)
        assertEquals("way/201",matcher.match(fix()).match?.road?.id)
        assertEquals(50,evaluate(matcher,DrivingLimitPipeline(),fix()).upcoming?.mph)
    }
    @Test fun malformedNearbyGeometryIsUncertainAndDoesNotEscapeToLegacyNumericLimit() {
        val r=road(201,listOf(p,Geo.ahead(p,0.0,300.0)),tags())
        install(listOf(r),malformed=true)
        val m=RegionalPackMatcher(context)
        assertEquals(RoadProviderState.ROAD_MATCH_UNCERTAIN,m.match(fix()).state)
        assertNull(evaluate(m,DrivingLimitPipeline(),fix(),listOf(r.copy(tags=tags("50 mph")))).decision.mph)
    }
    @Test fun missingPackAndCorruptPointerDoNotCrashAndCannotInventAnIdentity() {
        val store=RegionalPackStore(context)
        val active=File(context.filesDir,"regional-road-packs/active").apply { mkdirs() }
        File(active,"lancashire.json").writeText("broken json")
        assertTrue(store.installed().isEmpty())
        val result=RegionalPackMatcher(context).match(fix())
        assertNull(result.match);assertTrue(result.state in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE))
    }
    @Test fun activationAndDeletionDiscardPreviousPackAuthorityButPreserveOwnerAliases() {
        val r=road(201,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,300.0)),tags("50 mph"))
        install(listOf(r));val m=RegionalPackMatcher(context);val pipeline=DrivingLimitPipeline()
        assertEquals(50,evaluate(m,pipeline,fix()).decision.mph)
        val generation=m.match(fix()).generation
        install(listOf(r.copy(tags=tags("20 mph"))))
        assertNotEquals(generation,m.match(fix(t=2000)).generation)
        assertEquals(20,evaluate(m,pipeline,fix(t=2000)).decision.mph)
        val owner=RoadDb.Override("osm:201",0.0,30,point=p,sharedAcrossDirections=true)
        assertEquals(30,evaluate(m,pipeline,fix(t=3000),owners=listOf(owner)).decision.mph)
        RegionalPackStore(context).delete("lancashire")
        assertEquals(30,evaluate(m,pipeline,fix(t=4000),listOf(r),listOf(owner)).decision.mph)
        assertEquals("osm:201",owner.road)
    }
    private fun field(name: String)=DrivingService::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun await(predicate: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<until) { if(predicate())return;Thread.sleep(20) }
        assertTrue("Timed out waiting for actual service decision",predicate())
    }
    private fun start() {
        instrumentation.runOnMainSync {
            val s=DrivingService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply { isAccessible=true }.invoke(s,context)
            s.onCreate();service=s
        }
        await { field("ready").getBoolean(service) }
    }
    private fun send(point: GeoPoint=p,bearing: Double=0.0): Long {
        val t=SystemClock.elapsedRealtime()
        val location=Location("gps").apply { latitude=point.lat;longitude=point.lon;accuracy=5f;speed=10f;this.bearing=bearing.toFloat();elapsedRealtimeNanos=t*1_000_000 }
        instrumentation.runOnMainSync { service!!.onLocationChanged(location) }
        await { DriveBus.state.value.roadDecisionElapsedMs==t }
        return t
    }
    private fun camera(point: GeoPoint,direction: Double): Camera {
        val c=Camera("cycle2-${java.util.UUID.randomUUID()}",point,CameraType.SPEED,CameraSource.USER,direction,30)
        CameraDb(context).use { it.upsert(c) };cameraIds+=c.id;return c
    }
    @Test fun actualServiceUsesOfflineRegionalGeometryForTurnsAndUpcomingChanges() {
        val j=Geo.ahead(p,0.0,120.0)
        install(listOf(road(201,listOf(Geo.ahead(p,180.0,300.0),j),tags()),
            road(202,listOf(j,Geo.ahead(j,0.0,300.0)),tags("50 mph")),
            road(203,listOf(j,Geo.ahead(j,90.0,300.0)),tags("20 mph")+mapOf("name" to "Side Road"))))
        start();send()
        val state=DriveBus.state.value
        assertEquals(50,state.upcoming?.mph);assertEquals(20,state.turns.single { it.direction==TurnDirection.RIGHT }.mph)
        assertNull(field("liveRoadRequest").get(service));assertFalse(field("fetching").getBoolean(service))
    }
    @Test fun actualServiceRejectsParallelCarriagewayCameraWithoutLegacyDownloads() {
        val current=road(201,listOf(Geo.ahead(p,180.0,300.0),Geo.ahead(p,0.0,500.0)),tags())
        val parallel=current.copy(id="way/202",points=current.points.map { Geo.ahead(it,90.0,20.0) })
        install(listOf(current,parallel));camera(Geo.ahead(Geo.ahead(p,0.0,150.0),90.0,20.0),0.0)
        start();send()
        assertNull(DriveBus.state.value.alert)
        assertEquals("Different carriageway",DriveBus.state.value.decision.reason)
        assertNull(field("liveRoadRequest").get(service))
    }
    @Test fun actualServicePreservesForwardAndReverseCameraApproachesOnRegionalRoads() {
        val current=road(201,listOf(Geo.ahead(p,180.0,500.0),Geo.ahead(p,0.0,500.0)),tags())
        install(listOf(current));val forward=camera(Geo.ahead(p,0.0,150.0),0.0);val reverse=camera(Geo.ahead(p,180.0,150.0),180.0)
        start();send();assertEquals(forward.id,DriveBus.state.value.alert?.camera?.id)
        Thread.sleep(10);send(bearing=180.0);assertEquals(reverse.id,DriveBus.state.value.alert?.camera?.id)
    }
}
