package uk.co.traynor.speedbuddy

import android.content.ContextWrapper
import android.location.Location
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Real installed RTree pack -> matcher -> production service/pipeline; no fabricated regional results. */
class RegionalDrivingCycleTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val origin=GeoPoint(53.5,-2.8)
    private val junction=Geo.ahead(origin,0.0,80.0)
    private var service: DrivingService? = null
    @After fun cleanup() {
        service?.let { instrumentation.runOnMainSync { it.onDestroy() } }
        RegionalPackStore(context).delete("lancashire")
    }
    private fun digest(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
    private fun install(directional: Boolean=false,sameLimit: Boolean=false) {
        RegionalPackStore(context).delete("lancashire")
        RegionalPackStore(context).delete("merseyside")
        val raw=File(context.cacheDir,"cycle1.sqlite").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            for((key,value) in mapOf("formatVersion" to "1","matcherVersion" to "\"distance-heading-oneway-continuity-v1\"","dataset" to "{\"region\":\"lancashire\"}",
                "coverage" to "[[[[-3.0,53.0],[-2.0,53.0],[-2.0,54.0],[-3.0,54.0],[-3.0,53.0]]]]"))
                db.execSQL("INSERT INTO metadata VALUES(?,?)",arrayOf(key,value))
            db.execSQL("CREATE TABLE roads(osm_way_id INTEGER PRIMARY KEY,coordinates TEXT NOT NULL,tags TEXT NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE roads_rtree USING rtree(osm_way_id,min_lon,max_lon,min_lat,max_lat)")
            fun insert(id: Int,points: List<GeoPoint>,tags: Map<String,String>) {
                db.execSQL("INSERT INTO roads VALUES(?,?,?)",arrayOf<Any>(id,JSONArray(points.map { listOf(it.lon,it.lat) }).toString(),JSONObject(tags).toString()))
                db.execSQL("INSERT INTO roads_rtree VALUES(?,?,?,?,?)",arrayOf<Any>(id,points.minOf { it.lon },points.maxOf { it.lon },points.minOf { it.lat },points.maxOf { it.lat }))
            }
            insert(101,listOf(origin,junction),mapOf("highway" to "residential","name" to "Test Road","maxspeed" to "30 mph")+
                if(directional) mapOf("maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph") else emptyMap())
            if(!directional) insert(102,listOf(junction,Geo.ahead(junction,90.0,500.0)),mapOf("highway" to "residential","name" to "Test Road","maxspeed" to if(sameLimit) "30 mph" else "50 mph"))
        }
        val gzip=File(context.cacheDir,"cycle1.gz")
        GZIPOutputStream(gzip.outputStream()).use { raw.inputStream().use { input -> input.copyTo(it) } }
        // Different fixture contents need different immutable versions, just like real packs.
        val version="cycle1-${java.util.UUID.randomUUID()}"
        val descriptor=RegionalPackDescriptor("lancashire","Lancashire",version,1,"2026-10-08T00:00:00Z",gzip.length(),raw.length(),digest(gzip),digest(raw),
            "https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/$version/roads.sqlite.gz","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/$version/manifest.json")
        RegionalPackStore(context).install(descriptor,gzip)
    }
    private fun fix(point: GeoPoint,bearing: Double,t: Long)=Fix(point,5.0,10.0,1.0,bearing,t)
    @Test fun realRegionalGeometrySelectsBothDirectionalLimitsThroughDecisionPipeline() {
        install(true)
        for((bearing,mph) in listOf(0.0 to 30,180.0 to 50)) {
            val fix=fix(Geo.ahead(origin,0.0,40.0),bearing,1000)
            val regional=RegionalPackMatcher(context).match(fix)
            assertEquals("way/101",regional.match!!.road.id)
            val result=DrivingLimitPipeline().evaluate(fix,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10_000,regional)
            assertEquals(mph,result.source);assertEquals(mph,result.decision.mph)
        }
    }
    @Test fun realPackHonoursLegacyCorrectionsAndLearnedBoundaryAliasesWithoutMutatingRecords() {
        install()
        val fix=fix(Geo.ahead(origin,0.0,40.0),0.0,1000)
        val regional=RegionalPackMatcher(context).match(fix)
        val row=RoadDb.Override("osm:101",0.0,20)
        val result=DrivingLimitPipeline().evaluate(fix,emptyList(),listOf(row),emptyMap(),emptyList(),emptyList(),1000,10_000,regional)
        assertEquals(20,result.decision.mph);assertTrue(result.decision.ownerApplied)
        assertEquals("osm:101",row.road)
        val boundary=BoundaryCorrection("osm:101","way/102",30,50,junction,Geo.ahead(junction,90.0,60.0),90.0,5.0,5.0,.95,0.0,10_000)
        val nextFix=fix(Geo.ahead(junction,90.0,30.0),90.0,2000)
        val next=RegionalPackMatcher(context).match(nextFix)
        val before=DrivingLimitPipeline().evaluate(nextFix,emptyList(),emptyList(),emptyMap(),listOf(boundary),emptyList(),2000,11_000,next)
        assertEquals(30,before.decision.mph)
        assertEquals("osm:101",boundary.fromId)
    }
    @Test fun samePersistentPipelineImmediatelyChangesAuthorityWhenDirectionalTravelReverses() {
        install(true)
        val matcher=RegionalPackMatcher(context);val pipeline=DrivingLimitPipeline()
        val point=Geo.ahead(origin,0.0,40.0)
        val backward=fix(point,180.0,1000)
        assertEquals(50,pipeline.evaluate(backward,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10_000,matcher.match(backward)).decision.mph)
        // A gradual U-turn passes weak/perpendicular headings; they cannot replace direction evidence.
        for((index,bearing) in listOf(135.0,90.0,45.0).withIndex()) {
            val intermediate=fix(point,bearing,2000L+index*1000)
            pipeline.evaluate(intermediate,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),intermediate.elapsedMs,10_000+intermediate.elapsedMs,matcher.match(intermediate))
        }
        val forward=fix(point,0.0,5000)
        val changed=pipeline.evaluate(forward,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),5000,15_000,matcher.match(forward))
        assertEquals(30,changed.source);assertEquals(30,changed.decision.mph);assertFalse(changed.decision.assumed)
    }
    @Test fun installedMatcherConfirmationClearsPendingCautionAcrossSameLimitTurn() {
        install(sameLimit=true)
        val matcher=RegionalPackMatcher(context);val pipeline=DrivingLimitPipeline();val voice=DeferredLimitVoice()
        fun evaluate(point: GeoPoint,bearing: Double?,at: Long,speed: Double=8.0): DriveLimitResult {
            val f=Fix(point,5.0,speed,1.0,bearing,at)
            val regional=matcher.match(f)
            assertEquals(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,regional.state)
            return pipeline.evaluate(f,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),at,at,regional)
        }
        val first=evaluate(Geo.ahead(origin,0.0,30.0),0.0,1000)
        assertEquals(30,first.presentation.mph);assertFalse(first.presentation.assumed)
        assertNull(voice.update(first.presentation.mph,false,true))
        val turned=evaluate(Geo.ahead(junction,90.0,30.0),90.0,2000)
        assertEquals("way/102",turned.road!!.road.id);assertEquals(30,turned.presentation.mph)
        assertFalse(turned.presentation.assumed);assertFalse(turned.presentation.changing)
        assertNull(voice.update(turned.presentation.mph,false,true))
        for(t in listOf(3000L,4000L,5000L)) {
            val stopped=evaluate(Geo.ahead(junction,90.0,30.0),null,t,0.0)
            assertEquals(30,stopped.presentation.mph);assertFalse(stopped.presentation.assumed)
        }
    }
    @Test fun installedMatcherDisplaysDifferentLimitImmediatelyBeyondConnectedBoundary() {
        install()
        val matcher=RegionalPackMatcher(context);val pipeline=DrivingLimitPipeline()
        val before=fix(Geo.ahead(origin,0.0,30.0),0.0,1000)
        val first=pipeline.evaluate(before,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),1000,1000,matcher.match(before))
        assertEquals(30,first.presentation.mph)
        val after=fix(Geo.ahead(junction,90.0,30.0),90.0,2000)
        val regional=matcher.match(after)
        assertEquals("way/102",regional.match!!.road.id)
        val next=pipeline.evaluate(after,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),2000,2000,regional)
        assertEquals(50,next.presentation.mph);assertFalse(next.presentation.changing);assertFalse(next.presentation.assumed)
    }
    private fun field(name: String)=DrivingService::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun startService() {
        instrumentation.runOnMainSync {
            val instance=DrivingService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply { isAccessible=true }.invoke(instance,context)
            instance.onCreate();service=instance
        }
        await { field("ready").getBoolean(service) }
    }
    private fun await(predicate: () -> Boolean) {
        val until=SystemClock.elapsedRealtime()+10_000
        while(SystemClock.elapsedRealtime()<until) { if(predicate()) return; Thread.sleep(20) }
        assertTrue("Timed out waiting for service decision",predicate())
    }
    private fun send(point: GeoPoint,bearing: Double,speed: Float=10f): Long {
        val time=SystemClock.elapsedRealtime()
        val location=Location("gps").apply { latitude=point.lat;longitude=point.lon;accuracy=5f;this.bearing=bearing.toFloat();this.speed=speed;elapsedRealtimeNanos=time*1_000_000 }
        instrumentation.runOnMainSync { service!!.onLocationChanged(location) }
        return time
    }
    @Test fun actualServiceRematchesWithinFiveSecondsAnd150Metres() {
        install();startService()
        val first=send(Geo.ahead(origin,0.0,30.0),0.0)
        await { DriveBus.state.value.road?.road?.id=="way/101" }
        DriveBus.freezeDiagnostics()
        try {
            val second=send(Geo.ahead(junction,90.0,30.0),90.0)
            assertTrue(second-first<5000)
            await { DriveBus.state.value.road?.road?.id=="way/102" }
            assertEquals(50,DriveBus.state.value.sourceLimitMph)
            assertEquals(second,DriveBus.state.value.fix!!.elapsedMs)
            assertEquals("way/101",DiagnosticsInspection.state.value!!.state.road!!.road.id)
            assertEquals(first,DiagnosticsInspection.state.value!!.state.fix!!.elapsedMs)
        } finally { DiagnosticsInspection.unfreeze() }
    }
    @Test fun actualServiceNeverPublishesDelayedOldFrameAndDrainsNewestPendingFix() {
        install();startService()
        RoadDecisionFlight.recorder.clear()
        val matcher=field("regionalMatcher").get(service)!!
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val blocker=Thread { synchronized(matcher) { entered.countDown();release.await(10,TimeUnit.SECONDS) } }.apply { start() }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        try {
            val delayed=send(Geo.ahead(origin,0.0,30.0),0.0)
            Thread.sleep(50)
            val newest=send(Geo.ahead(junction,90.0,40.0),90.0,12f)
            val currentSpeed=DriveBus.state.value.speedMph
            release.countDown();blocker.join(2000)
            await { DriveBus.state.value.road?.road?.id=="way/102" }
            assertEquals(newest,DriveBus.state.value.fix!!.elapsedMs)
            assertEquals(currentSpeed,DriveBus.state.value.speedMph)
            val events=RoadDecisionFlight.recorder.snapshot().events
            assertTrue(events.any { it.stage==FlightStage.REGIONAL && it.new.fixId==delayed })
            assertTrue(events.any { it.stage==FlightStage.REJECTED && it.new.fixId==delayed && it.cause=="regional superseded" })
            assertEquals(newest,RoadDecisionFlight.recorder.snapshot().current!!.fixId)
        } finally { release.countDown() }
    }
    @Test fun delayedNetworkResponseCannotOverwriteNewRegionalDecisionOrBlockGpsMatching() {
        RegionalPackStore(context).delete("lancashire");RegionalPackStore(context).delete("merseyside")
        startService()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val client=SpeedBuddyRoadClient(credential={ "test-credential" },open={ url ->
            object: java.net.HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy()=false
                override fun getResponseCode(): Int { entered.countDown();release.await(10,TimeUnit.SECONDS);return 200 }
                override fun getInputStream()= """{"providerState":"ROAD_MATCHED_LIMIT_KNOWN","matched":true,"limit":{"mph":50},"fallbackAllowed":false}""".byteInputStream()
            }
        })
        instrumentation.runOnMainSync { field("speedBuddyRoadClient").set(service,client) }
        try {
            send(Geo.ahead(origin,0.0,30.0),0.0)
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            install()
            val newest=send(Geo.ahead(origin,0.0,40.0),0.0,12f)
            await { DriveBus.state.value.sourceLimitMph==30 }
            val speed=DriveBus.state.value.speedMph
            release.countDown()
            await { !(field("liveRoadRequest").get(service) as kotlinx.coroutines.Job).isActive }
            assertEquals(newest,DriveBus.state.value.fix!!.elapsedMs)
            assertEquals(30,DriveBus.state.value.sourceLimitMph)
            assertEquals(speed,DriveBus.state.value.speedMph)
        } finally { release.countDown() }
    }
}
