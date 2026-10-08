package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LimitCorrectionDbTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun correctionEvidenceSurvivesRefreshReopenAndBackupRestore() {
        val p=GeoPoint(53.5,-2.8)
        val r=Road("way/current","Test",listOf(p,Geo.ahead(p,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
        val f=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
        val row=QuickLimitCorrection.capture(f,RoadMatch(r,0.0,0.0,.95),30,40,r.id,1000)!!
        context.deleteDatabase("learning-test.db");context.deleteDatabase("learning-restore.db")
        RoadDb(context,"learning-test.db").use { db ->
            db.saveOverride(row)
            db.replace(RoadTileData(RoadTile.at(p),1000,listOf(r),emptyList()))
            db.replace(RoadTileData(RoadTile.at(p),2000,listOf(r.copy(tags=mapOf("maxspeed" to "50 mph"))),emptyList()))
        }
        RoadDb(context,"learning-test.db").use { db ->
            assertEquals(row,db.overrides().single());assertEquals(40,db.overrideFor(r.id,0.0));assertNull(db.overrideFor(r.id,180.0))
            val text=OwnerBackupCodec.export(emptyList(),context.getSharedPreferences("learning-test",0),roadOverrides = db.overrides())
            val parsed=OwnerBackupCodec.parse(text)
            RoadDb(context,"learning-restore.db").use { restored ->
                restored.mergeOwnerCorrections(parsed.roadOverrides,parsed.boundaries)
                assertEquals(row,restored.overrides().single())
                restored.resetCorrections();assertTrue(restored.overrides().isEmpty())
            }
        }
    }
    @Test fun unknownAndNationalSelectionsPersistAndRemainDirectionScoped() {
        context.deleteDatabase("learning-test.db")
        RoadDb(context,"learning-test.db").use { it.setOverride("way/1",0.0,0);it.setOverride("way/2",90.0,-1) }
        RoadDb(context,"learning-test.db").use {
            assertEquals(0,it.overrideFor("way/1",0.0));assertEquals(-1,it.overrideFor("way/2",90.0))
            assertNull(it.overrideFor("way/1",180.0))
        }
    }
    @Test fun v1RoadDatabaseUpgradePreservesExistingCorrections() {
        context.deleteDatabase("learning-upgrade.db")
        val db=context.openOrCreateDatabase("learning-upgrade.db",0,null)
        db.execSQL("CREATE TABLE overrides(road TEXT NOT NULL,bearing REAL NOT NULL,mph INTEGER NOT NULL,PRIMARY KEY(road,bearing))")
        db.execSQL("INSERT INTO overrides VALUES('way/legacy',0,40)");db.version=1;db.close()
        RoadDb(context,"learning-upgrade.db").use { assertEquals(40,it.overrideFor("way/legacy",0.0)) }
    }
    @Test fun sharedOrdinaryCorrectionSurvivesReopenBackupAndAliasReset() {
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,400.0)),mapOf("highway" to "residential","maxspeed" to "30 mph"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val row=QuickLimitCorrection.capture(fix,RoadMatch(road,0.0,0.0,.95),30,20,road.id,1000)!!
        context.deleteDatabase("shared-cycle.db")
        RoadDb(context,"shared-cycle.db").use { it.saveOverride(row.copy(road="osm:123"));it.setOverride("way/456",180.0,50) }
        RoadDb(context,"shared-cycle.db").use { db ->
            assertEquals(20,db.overrideFor("way/123",180.0));assertEquals(50,db.overrideFor("way/456",180.0))
            val backup=OwnerBackupCodec.parse(OwnerBackupCodec.export(emptyList(),context.getSharedPreferences("shared-cycle",0),roadOverrides=db.overrides()))
            assertTrue(backup.roadOverrides.first { it.road=="osm:123" }.sharedAcrossDirections)
            assertFalse(backup.roadOverrides.first { it.road=="way/456" }.sharedAcrossDirections)
            val boundary=BoundaryCorrection("way/120","way/123",30,50,p,Geo.ahead(p,0.0,50.0),180.0,5.0,5.0,.95,0.0,10_000,sharedAcrossDirections=true)
            db.saveSelection(LimitSelectionPlan(boundary=boundary,kind="boundary correction",message="saved"),"{}")
            // A shared boundary learned on the return trip must supersede the shared tap,
            // even though the original tap was recorded under the opposite heading/old alias.
            assertNull(db.overrideFor("way/123",180.0));assertEquals(boundary,db.boundaries().single())
            db.deleteOwnerCorrections("way/123")
            assertTrue(db.boundaries().isEmpty())
            assertNull(db.overrideFor("osm:123",0.0));assertEquals(50,db.overrideFor("way/456",180.0))
        }
    }
}
