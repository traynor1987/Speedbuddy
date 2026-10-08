package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoadDbTest {
    @Test fun oversizedParentFetchesCurrentChildOnceAndPersistsTheSubdivision() {
        runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val point=GeoPoint(53.41,-2.99);val parent=RoadTile.at(point);val requested=mutableListOf<RoadTile>()
        val road=Road("way/dense",null,listOf(point,GeoPoint(point.lat+.0002,point.lon+.0002)),mapOf("highway" to "residential","maxspeed" to "20 mph"))
        context.deleteDatabase("adaptive-dense.db")
        RoadDb(context,"adaptive-dense.db").use { db ->
            val download=AdaptiveRoadDownloader(db) { tile,_ ->
                requested+=tile
                if(tile==parent) throw RoadResponseTooLargeException(4_000_001)
                RoadTileData(tile,1000,listOf(road),emptyList())
            }
            val result=download.fetch(parent,point)
            assertEquals(listOf(parent,parent.childContaining(point)),requested)
            assertEquals(1,result.subdivisionLevel)
            assertTrue(parent in db.subdivisions())
            RoadCacheUpdater(db).store(result.data)
            assertTrue(result.requested in db.coverage())
            assertFalse(parent in db.coverage())
        }
        context.deleteDatabase("adaptive-dense.db")
        }
    }
    @Test fun denseChildCoverageIsDurableButDoesNotMarkTheParentComplete() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val point=GeoPoint(53.41,-2.99);val parent=RoadTile.at(point);val child=parent.childContaining(point)
        val road=Road("way/dense",null,listOf(point,GeoPoint(point.lat+.0002,point.lon+.0002)),mapOf("highway" to "residential","maxspeed" to "20 mph"))
        RoadDb(context,"dense-child.db").use { db ->
            db.markSubdivided(parent)
            db.replace(RoadTileData(child,1000,listOf(road),emptyList()))
            assertTrue(child in db.coverage())
            assertFalse(parent in db.coverage())
            assertTrue(parent in db.subdivisions())
            assertEquals("way/dense",db.nearby(point).roads.single().road.id)
        }
        context.deleteDatabase("dense-child.db")
    }
    @Test fun laterDenseFetchStartsAtTheRecordedChildInsteadOfRetryingItsOversizedParent() {
        runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val point=GeoPoint(53.41,-2.99);val parent=RoadTile.at(point);val requested=mutableListOf<RoadTile>()
        val road=Road("way/dense",null,listOf(point,GeoPoint(point.lat+.0002,point.lon+.0002)),mapOf("highway" to "residential","maxspeed" to "20 mph"))
        context.deleteDatabase("dense-retry.db")
        RoadDb(context,"dense-retry.db").use { db ->
            db.markSubdivided(parent)
            val result=AdaptiveRoadDownloader(db) { tile,_ -> requested+=tile;RoadTileData(tile,2000,listOf(road),emptyList()) }.fetch(parent,point)
            assertEquals(listOf(parent.childContaining(point)),requested)
            assertEquals(1,result.subdivisionLevel)
        }
        context.deleteDatabase("dense-retry.db")
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val point = GeoPoint(53.5,-2.8)
    private val tile = RoadTile.at(point)
    private val road = Road("way/1","Saved road",listOf(point,Geo.ahead(point,0.0,300.0)),mapOf("maxspeed" to "30 mph"))
    private fun response(geometry: String = "[{\"lat\":53.5,\"lon\":-2.8},{\"lat\":53.502,\"lon\":-2.8}]", count: Int = 1) =
        """{"elements":[{"type":"way","id":1,"nodes":[10,11],"tags":{"highway":"residential","maxspeed":"30 mph","irrelevant":"ignore"},"geometry":$geometry},{"type":"count","tags":{"total":"$count"}}]}"""
    @Test fun persistentOfflineRoadAndNewestVersionSurviveReopen() {
        context.deleteDatabase("road-test.db")
        RoadDb(context,"road-test.db").use { it.replace(RoadTileData(tile,1000,listOf(road),emptyList())) }
        RoadDb(context,"road-test.db").use {
            assertEquals(road,it.nearby(point).roads.single().road)
            val fix=Fix(Geo.ahead(point,0.0,100.0),5.0,10.0,1.0,0.0,1000)
            assertEquals(30,OsmSpeedLimitProvider().limit(RoadMatcher().match(fix,it.nearby(point).roads.map { row -> row.road })))
            it.replace(RoadTileData(tile,2000,listOf(road.copy(tags=mapOf("maxspeed" to "20 mph"))),emptyList()))
            assertEquals(20,SpeedLimits.mph(it.nearby(point).roads.single().road.tags))
        }
        RoadDb(context,"road-test.db").use { assertEquals(20,SpeedLimits.mph(it.nearby(point).roads.single().road.tags)) }
    }
    @Test fun failedMalformedAndPartialRefreshKeepPriorDataset() {
        context.deleteDatabase("road-test.db")
        RoadDb(context,"road-test.db").use { db ->
            db.replace(RoadTileData(tile,1000,listOf(road),emptyList()))
            assertTrue(runCatching { RoadCacheUpdater(db).refresh(tile) { throw java.io.IOException("offline") } }.isFailure)
            assertEquals(1000L,db.coverage()[tile])
            val failures = listOf("{",response(count=2),response(geometry="[]"),response().dropLast(4), response().replace("[10,11]","[10,11,12]"),
                response().replace("\"elements\":", "\"remark\":\"runtime error: timed out\",\"elements\":"))
            failures.forEach { raw ->
                assertTrue(runCatching { RoadCacheUpdater(db).refresh(tile) { RoadResponse.decode(raw,tile,2000) } }.isFailure)
                assertEquals(30,SpeedLimits.mph(db.nearby(point).roads.single().road.tags))
                assertEquals(1000L,db.coverage()[tile])
            }
            assertTrue(runCatching { db.replace(RoadTileData(tile,2000,listOf(road.copy(points=emptyList())),emptyList())) }.isFailure)
            assertEquals(1000L,db.coverage()[tile])
        }
    }
    @Test fun validRefreshStripsUnneededTagsAndAcceptsVerifiedEmptyTile() {
        val data = RoadResponse.decode(response(),tile,2000)
        assertFalse(data.roads.single().tags.containsKey("irrelevant"))
        assertEquals(30,SpeedLimits.mph(data.roads.single().tags))
        assertTrue(RoadResponse.decode("""{"elements":[{"type":"count","tags":{"total":"0"}}]}""",tile,2000).roads.isEmpty())
    }
    @Test fun databaseRollbackOnSqlFailureKeepsWorkingTile() {
        context.deleteDatabase("road-test.db")
        RoadDb(context,"road-test.db").use { db ->
            db.replace(RoadTileData(tile,1000,listOf(road),emptyList()))
            db.writableDatabase.execSQL("CREATE TRIGGER fail_new BEFORE INSERT ON roads WHEN NEW.road_id='way/fail' BEGIN SELECT RAISE(ABORT,'injected disk error'); END")
            assertTrue(runCatching { db.replace(RoadTileData(tile,2000,listOf(road.copy(id="way/fail")),emptyList())) }.isFailure)
            assertEquals(road,db.nearby(point).roads.single().road)
            assertEquals(1000L,db.coverage()[tile])
        }
    }
    @Test fun ownerOverrideAndBoundaryPersistWithoutModifyingSource() {
        context.deleteDatabase("road-test.db")
        val boundary = BoundaryCorrection("way/1","way/2",30,20,point,Geo.ahead(point,0.0,150.0),0.0,5.0,6.0,.9,2.0,1000,
            sharedAcrossDirections=true)
        RoadDb(context,"road-test.db").use {
            it.replace(RoadTileData(tile,1000,listOf(road),emptyList()))
            it.setOverride(road.id,0.0,40); it.saveBoundary(boundary)
        }
        RoadDb(context,"road-test.db").use {
            assertEquals(40,it.overrideFor(road.id,0.0)); assertNull(it.overrideFor(road.id,180.0))
            assertEquals(boundary,it.boundaries().single())
            assertEquals(30,SpeedLimits.mph(it.nearby(point).roads.single().road.tags))
            it.resetCorrections(); assertNull(it.overrideFor(road.id,0.0)); assertTrue(it.boundaries().isEmpty())
        }
    }
    @Test fun spatialLookupFindsTileSeamsAndExcludesDistantRoads() {
        context.deleteDatabase("road-test.db")
        RoadDb(context,"road-test.db").use { db ->
            val seam=GeoPoint(tile.north-.0001,point.lon)
            val crossing=road.copy(id="way/seam",points=listOf(Geo.ahead(seam,180.0,100.0),Geo.ahead(seam,0.0,400.0)))
            val far=RoadTile.at(Geo.ahead(point,90.0,25000.0))
            val remote=road.copy(id="way/far",points=road.points.map { Geo.ahead(it,90.0,25000.0) })
            db.replace(RoadTileData(tile,1000,listOf(crossing),emptyList()))
            db.replace(RoadTileData(far,1000,listOf(remote),emptyList()))
            val candidates=db.nearby(Geo.ahead(seam,0.0,200.0)).roads.map { it.road.id }
            assertTrue("way/seam" in candidates);assertFalse("way/far" in candidates)
        }
    }
    @Test fun cleanupRetainsCurrentCoverageAndOwnerData() {
        context.deleteDatabase("road-test.db")
        RoadDb(context,"road-test.db").use {
            it.replace(RoadTileData(tile,1000,listOf(road),emptyList()))
            val far = RoadTile.at(GeoPoint(50.0,-1.0))
            it.replace(RoadTileData(far,500,emptyList(),emptyList()))
            it.setOverride(road.id,0.0,40)
            it.cleanup(setOf(tile),maxTiles=1)
            assertTrue(tile in it.coverage()); assertFalse(far in it.coverage())
            assertEquals(40,it.overrideFor(road.id,0.0))
        }
    }
    @Test fun cleanupUsesAQueryApiForIncrementalVacuumAndKeepsTheSavedTile() {
        context.deleteDatabase("road-cleanup-query-api.db")
        RoadDb(context,"road-cleanup-query-api.db").use { db ->
            db.replace(RoadTileData(tile,1000,listOf(road),emptyList()))
            // Android 15 rejects PRAGMA incremental_vacuum through execSQL with
            // “Queries can be performed using SQLiteDatabase query or rawQuery”.
            // The real maintenance path must complete through rawQuery instead.
            db.cleanup(setOf(tile),maxTiles=1)
            assertEquals(road.id,db.nearby(point).roads.single().road.id)
        }
        context.deleteDatabase("road-cleanup-query-api.db")
    }
}
