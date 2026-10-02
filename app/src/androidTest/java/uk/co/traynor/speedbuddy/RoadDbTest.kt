package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoadDbTest {
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
        val boundary = BoundaryCorrection("way/1","way/2",30,20,point,Geo.ahead(point,0.0,150.0),0.0,5.0,6.0,.9,2.0,1000)
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
}
