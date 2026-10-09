package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RoadCacheTest {
    private val point = GeoPoint(53.5, -2.8)
    private fun fix(point: GeoPoint = this.point, heading: Double = 0.0, speed: Double = 12.0) = Fix(point, 5.0, speed, 1.0, heading, 1000)
    @Test fun targetCoverageIncludesTwentyMilesAndIsBounded() {
        val tiles = RoadTiles.covering(point)
        assertTrue(tiles.size in 35..90)
        listOf(0.0, 45.0, 90.0, 180.0, 270.0).forEach {
            assertTrue(RoadTile.at(Geo.ahead(point, it, 32_000.0)) in tiles)
        }
    }
    @Test fun savedCoverageIsPermanentAndNeverRefreshesJustBecauseItIsOld() {
        val now = 10_000_000L
        val saved = RoadTiles.covering(point).associateWith { now }
        val planner = RoadRefreshPlanner()
        assertNull(planner.next(fix(speed = 0.0), saved, now))
        assertNull(planner.next(fix(speed = 0.0), saved, now + ROAD_FRESH_MS + 900_000))
        assertEquals(now + ROAD_FRESH_MS + 900_000, planner.lastCheckMs)
    }
    @Test fun missingLocalTileComesFirstAndFailureBacksOff() {
        val planner = RoadRefreshPlanner()
        val tile = planner.next(fix(), emptyMap(), 1000)!!
        assertEquals(RoadTile.at(point), tile)
        planner.attempted(1000); planner.failed(1000)
        assertNull(planner.next(fix(), emptyMap(), 30_000))
        assertNotNull(planner.next(fix(), emptyMap(), 61_001))
    }
    @Test fun approachingCoverageEdgePrioritisesNextTileBeforeFifteenMinutes() {
        val planner = RoadRefreshPlanner()
        val local = RoadTile.at(point)
        val edge = GeoPoint(local.north - .001, point.lon)
        val saved = RoadTiles.covering(point).associateWith { 1000L }.toMutableMap()
        val ahead = RoadTile.at(Geo.ahead(edge, 0.0, 1800.0)); saved.remove(ahead)
        assertEquals(ahead, planner.next(fix(edge), saved, 2000))
    }
    @Test fun retentionLimitDoesNotRedownloadEvictedOuterTilesWhileStationary() {
        val planner=RoadRefreshPlanner()
        val local=RoadTile.at(point)
        val saved=mapOf(local to 1000L)
        planner.retentionLimited(point)
        assertNull(planner.next(fix(speed=0.0),saved,2000))
        assertNull(planner.next(fix(speed=0.0),saved,902_000))
        val far=Geo.ahead(point,0.0,8000.0)
        assertNotNull(planner.next(fix(far),saved,903_000))
    }
    @Test fun retentionLimitDoesNotRefreshPreviouslySavedOuterData() {
        val planner=RoadRefreshPlanner();val local=RoadTile.at(point)
        val outer=RoadTile.at(Geo.ahead(point,0.0,12000.0))
        planner.retentionLimited(point)
        val now=ROAD_FRESH_MS+2000
        assertNull(planner.next(fix(speed=0.0),mapOf(local to now,outer to 1000L),now))
    }
    @Test fun savedTilesDoNotKeepFillingTheTwentyMileCircle() {
        val planner = RoadRefreshPlanner()
        val local = RoadTile.at(point)
        val ahead = RoadTile.at(Geo.ahead(point, 0.0, 1800.0))
        val saved = mapOf(local to 1000L, ahead to 1000L)
        assertNull(planner.next(fix(speed = 12.0), saved, 1001))
    }
    @Test fun denseParentSelectsTheCurrentChildBeforeAnyOtherQuadrant() {
        val parent=RoadTile.at(point)
        val current=GeoPoint(parent.south+.001,parent.east-.001)
        val child=RoadSubdivision.next(parent,current,setOf(parent))
        assertEquals(1,child.level)
        assertTrue(child.contains(current))
        assertEquals(parent,child.copy(path=""))
    }
    @Test fun denseSubdivisionIsBoundedAtTheMinimumRegionSize() {
        val parent=RoadTile.at(point)
        var tile=parent
        repeat(MAX_ROAD_SUBDIVISION_DEPTH) { tile=tile.childContaining(point) }
        assertNull(RoadSubdivision.afterOversize(tile,point))
    }
    @Test fun completedDenseChildCountsAsUsefulCurrentCoverageWithoutCompletingParent() {
        val parent=RoadTile.at(point);val child=parent.childContaining(point)
        val planner=RoadRefreshPlanner()
        assertNull(planner.next(fix(),mapOf(child to 1000L),1000))
        assertNotNull(planner.next(fix(Geo.ahead(point,180.0,20_000.0)),mapOf(child to 1000L),1001))
    }
}
