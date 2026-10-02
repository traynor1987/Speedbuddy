package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CombinedRoadCacheTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val p=GeoPoint(53.5,-2.8)
    private val tile=RoadTile.at(p)
    private val road=Road("way/shared", "Shared",listOf(p,Geo.ahead(p,0.0,300.0)),mapOf("maxspeed" to "30 mph"))
    private val camera=Camera("node/shared",p,CameraType.COMBINED,CameraSource.OSM,0.0,30,updatedAtMs=1000,bidirectional=true)
    private val section=AverageSpeedSection("relation/shared",road.points,setOf(road.id),30)

    @Test fun downloadedRoadCameraDirectionAndAverageSectionRemainAvailableToMapAfterRestart() {
        context.deleteDatabase("roads.db");context.deleteDatabase("road-cache.db")
        try {
            RoadDb(context).use { it.replace(RoadTileData(tile,1000,listOf(road),listOf(camera),averageSections=listOf(section))) }
            val snapshot=OsmDataSource(context,"combined-test").cached(p)!!
            assertEquals(road,snapshot.roads.single());assertEquals(camera,snapshot.cameras.single())
            assertEquals(section,snapshot.averageSections.single())
            assertTrue(snapshot.cameras.single().bidirectional)
            val fix=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
            val match=RoadMatcher().match(fix,snapshot.roads)!!
            assertEquals(30,LimitDecisionEngine().decide(fix,match,SpeedLimits.mph(match.road.tags),null,emptyList(),1000).mph)
        } finally { context.deleteDatabase("roads.db");context.deleteDatabase("road-cache.db") }
    }
    @Test fun partialMapRefreshKeepsCompleteCoverageAndOtherDownloadedRoads() {
        context.deleteDatabase("combined-road.db")
        val other=road.copy(id="way/other")
        RoadDb(context,"combined-road.db").use { db ->
            db.replace(RoadTileData(tile,1000,listOf(road,other),listOf(camera),averageSections=listOf(section)))
            db.saveOverride(RoadDb.Override(road.id,0.0,20))
            val changed=road.copy(tags=mapOf("maxspeed" to "40 mph"))
            db.mergeMapSnapshot(OsmSnapshot(p,2000,listOf(changed),emptyList()))
            assertEquals(1000L,db.coverage()[tile])
            assertEquals(setOf(road.id,other.id),db.nearby(p).roads.map { it.road.id }.toSet())
            assertEquals(40,SpeedLimits.mph(db.nearby(p).roads.first { it.road.id==road.id }.road.tags))
            assertEquals(20,RoadDb.selectOverride(db.overrides(),road.id,0.0))
            assertEquals(section,db.nearby(p).averageSections.single())
        }
        context.deleteDatabase("combined-road.db")
    }
    @Test fun savedMapCoverageIsReconciledWhenAPartialTileContainsAnUnrelatedRoad() {
        val name="combined-partial.db";context.deleteDatabase(name)
        val otherPoint=Geo.ahead(p,90.0,250.0)
        val other=road.copy(id="way/unrelated",points=listOf(otherPoint,Geo.ahead(otherPoint,0.0,300.0)))
        RoadDb(context,name).use { db ->
            // The partial tile is newer; that must not hide missing valid older regional roads.
            val now=System.currentTimeMillis()
            db.replace(RoadTileData(tile,now-1000,listOf(other),emptyList(),complete=false))
            val snapshot=OsmSnapshot(p,now-2000,listOf(road),listOf(camera),listOf(section))
            val repository=DrivingRoadRepository(db) { snapshot }
            val local=repository.nearby(p,false)
            assertEquals(setOf(road.id,other.id),local.roads.map { it.road.id }.toSet())
            val fix=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
            val match=RoadMatcher().match(fix,local.roads.map { it.road })!!
            assertEquals(road.id,match.road.id)
            assertEquals(30,LimitDecisionEngine().decide(fix,match,SpeedLimits.mph(match.road.tags),null,emptyList(),1000).mph)
            assertEquals(camera,local.cameras.single());assertEquals(section,local.averageSections.single())
            assertTrue(db.coverage().isEmpty())
        }
        context.deleteDatabase(name)
    }
    @Test fun expiredRegionalCacheDoesNotReplaceReadablePartialTile() {
        val name="combined-expired.db";context.deleteDatabase(name)
        RoadDb(context,name).use { db ->
            val now=System.currentTimeMillis()
            val other=road.copy(id="way/unrelated")
            db.replace(RoadTileData(tile,now-1000,listOf(other),emptyList(),complete=false))
            val expired=OsmSnapshot(p,now-2_592_000_001L,listOf(road),listOf(camera),listOf(section))
            val local=DrivingRoadRepository(db) { expired }.nearby(p,false)
            assertEquals(listOf(other),local.roads.map { it.road })
            assertTrue(local.cameras.isEmpty());assertTrue(local.averageSections.isEmpty())
            assertTrue(db.coverage().isEmpty())
        }
        context.deleteDatabase(name)
    }
    @Test fun versionTwoRoadStoreUpgradesWithoutLosingCachedRoadsOrOwnerOverrides() {
        val name="combined-v2.db";context.deleteDatabase(name)
        RoadDb(context,name).use { db ->
            db.replace(RoadTileData(tile,1000,listOf(road),listOf(camera)))
            db.saveOverride(RoadDb.Override(road.id,0.0,20))
            db.writableDatabase.execSQL("DROP TABLE average_sections")
            db.writableDatabase.version=2
        }
        RoadDb(context,name).use { db ->
            assertEquals(3,db.readableDatabase.version)
            assertEquals(road,db.nearby(p).roads.single().road)
            assertEquals(20,RoadDb.selectOverride(db.overrides(),road.id,0.0))
            db.replace(RoadTileData(tile,2000,listOf(road),listOf(camera),averageSections=listOf(section)))
            assertEquals(section,db.nearby(p).averageSections.single())
        }
        context.deleteDatabase(name)
    }
}
