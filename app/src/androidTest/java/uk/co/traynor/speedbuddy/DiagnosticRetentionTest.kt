package uk.co.traynor.speedbuddy

import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRetentionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun boundedDiagnosticsPersistOnRestartAndClearKeepsOwnerEvidence() {
        val name="cycle3-diagnostics.db";context.deleteDatabase(name)
        val p=GeoPoint(53.5,-2.8)
        val override=RoadDb.Override("way/123",0.0,30,point=p,sharedAcrossDirections=true)
        val boundary=BoundaryCorrection("way/123","way/124",30,20,p,Geo.ahead(p,0.0,30.0),0.0,5.0,5.0,.95,0.0,1)
        RoadDb(context,name).use { db ->
            db.saveOverride(override);db.saveBoundary(boundary)
            for(i in 1..510) db.recordDiagnostic(JSONObject().put("at",System.currentTimeMillis()+i).put("i",i).put("location",RoadJson.point(p)).toString())
            assertEquals(500,db.diagnostics().size)
        }
        val late=JSONObject().put("at",System.currentTimeMillis()-1000).put("location",RoadJson.point(p)).toString()
        RoadDb(context,name).use { db ->
            assertEquals(500,db.diagnostics().size);assertEquals(510,JSONObject(db.diagnostics().first()).getInt("i"))
            db.clearDiagnostics();db.recordDiagnostic(late)
            assertTrue(db.diagnostics().isEmpty());assertEquals(override,db.overrides().single());assertEquals(boundary,db.boundaries().single())
            db.recordDiagnostic(JSONObject().put("at",System.currentTimeMillis()+1000).put("kind","new session").toString())
            assertEquals(1,db.diagnostics().size)
        }
        context.deleteDatabase(name)
    }
    @Test fun maintenanceConvertsAnOldDatabaseAndActuallyReclaimsFreePages() {
        val name="cycle3-maintenance.db";context.deleteDatabase(name)
        RoadDb(context,name).use { it.setOverride("way/owner",0.0,40) }
        val path=context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA auto_vacuum=NONE");db.execSQL("VACUUM")
            db.execSQL("CREATE TABLE maintenance_test(value BLOB)")
            repeat(100) { db.execSQL("INSERT INTO maintenance_test VALUES(zeroblob(8192))") }
            db.execSQL("DELETE FROM maintenance_test")
        }
        val before=path.length()
        RoadDb(context,name).use { db -> db.maintainStorage();assertEquals(40,db.overrideFor("way/owner",0.0)) }
        assertTrue("Maintenance must reclaim actual database bytes",path.length()<before)
        SQLiteDatabase.openDatabase(path.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA auto_vacuum",null).use { it.moveToFirst();assertEquals(2,it.getInt(0)) }
        }
        context.deleteDatabase(name)
    }
    @Test fun concurrentSelectionAndDiagnosticWritesUseConsistentLockOrdering() {
        val name="cycle3-diagnostic-concurrency.db";context.deleteDatabase(name)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2);val start=java.util.concurrent.CountDownLatch(1)
        val selection=RoadDb(context,name);val logger=RoadDb(context,name)
        try {
            selection.writableDatabase;logger.writableDatabase
            val saves=pool.submit { start.await();repeat(100) { i ->
                selection.saveSelection(LimitSelectionPlan(override=RoadDb.Override("way/owner",0.0,30),kind="override",message="saved"),JSONObject().put("at",System.currentTimeMillis()).put("i",i).toString())
            } }
            val logs=pool.submit { start.await();repeat(100) { i -> logger.recordDiagnostic(JSONObject().put("at",System.currentTimeMillis()).put("i",i).toString()) } }
            start.countDown();saves.get(10,java.util.concurrent.TimeUnit.SECONDS);logs.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(30,selection.overrideFor("way/owner",0.0));assertEquals(200,logger.diagnostics().size)
        } finally { pool.shutdownNow();selection.close();logger.close();context.deleteDatabase(name) }
    }

}
