package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerBackupTest {
    private fun isolated(block: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        try { block(context) } finally { directory.deleteRecursively() }
    }
    private val legacy = """{"format":"speed-buddy-owner-backup","version":8,
      "cameras":[{"id":"saved","lat":53.1,"lon":-2.8,"type":"SPEED","direction":90,"mph":30,"note":"Home","updated":123,"bidirectional":true,"junctionId":null}],
      "settings":{"cameraSound":false,"tolerance":5,"theme":"light"},
      "cameraCorrections":[{"id":"node/123","direction":180}],"suppressedCameraIds":["node/456"],
      "roadLimits":{"way/1":20},"roadCorrections":[],"cameraAliases":[],"junctions":[]}"""

    @Test fun legacyRestoreRetainsFullOriginalAndReexportsUnsupportedOwnerData() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        val backup = OwnerBackupCodec.parse(legacy)
        val store = OwnerBackupStore(context)
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            store.restore(backup, cameras, roads, prefs)
            assertEquals(backup.cameras, cameras.userCameras())
            assertFalse(prefs.getBoolean("cameraSound", true))
            assertEquals(5, prefs.getInt("tolerance", 2))
        } }
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            assertEquals(legacy, OwnerBackupStore(context).legacyArchives().single())
            val exported = OwnerBackupCodec.export(cameras.userCameras(), prefs, roads.overrides(), roads.boundaries(), store.legacyArchives())
            val roundTrip = OwnerBackupCodec.parse(exported)
            assertEquals(legacy, roundTrip.legacyArchives.single())
            assertTrue(roundTrip.archivedOnly.contains("suppressed cameras"))
        } }
        prefs.edit().clear().commit()
    }
    @Test fun roadCorrectionsRoundTripAndSurviveDatabaseReopen() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        val point = GeoPoint(53.1, -2.8)
        val boundary = BoundaryCorrection("way/1", "way/2", 30, 20, point, Geo.ahead(point, 90.0, 100.0), 90.0, 5.0, 5.0, .9, 2.0, 123)
        val text = RoadDb(context, "original.db").use { source ->
            source.setOverride("way/1", 90.0, 30); source.saveBoundary(boundary)
            OwnerBackupCodec.export(emptyList(), prefs, source.overrides(), source.boundaries())
        }
        val parsed = OwnerBackupCodec.parse(text)
        CameraDb(context).use { cameras -> RoadDb(context, "restored.db").use { roads ->
            OwnerBackupStore(context).restore(parsed, cameras, roads, prefs)
        } }
        RoadDb(context, "restored.db").use { roads ->
            assertEquals(30, roads.overrideFor("way/1", 90.0)); assertNull(roads.overrideFor("way/1", 270.0))
            assertEquals(boundary, roads.boundaries().single())
        }
        prefs.edit().clear().commit()
    }
    @Test fun archiveFailurePreventsOwnerDataMutation() = isolated { context ->
        File(context.filesDir, "owner-backups").writeText("storage unavailable")
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        prefs.edit().putBoolean("cameraSound", true).commit()
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            assertTrue(runCatching { OwnerBackupStore(context).restore(OwnerBackupCodec.parse(legacy), cameras, roads, prefs) }.isFailure)
            assertTrue(cameras.userCameras().isEmpty())
            assertTrue(prefs.getBoolean("cameraSound", false))
        } }
        prefs.edit().clear().commit()
    }
    @Test fun futureAndMalformedBackupsNeverReachDatabaseWrites() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            for (invalid in listOf("{", legacy.replace("\"version\":8", "\"version\":100"), legacy.replace("\"lat\":53.1", "\"lat\":999"))) {
                assertTrue(runCatching { OwnerBackupStore(context).restore(OwnerBackupCodec.parse(invalid), cameras, roads, prefs) }.isFailure)
            }
            assertTrue(cameras.userCameras().isEmpty()); assertTrue(roads.overrides().isEmpty())
            assertFalse(File(context.filesDir, "owner-backups").exists())
        } }
        prefs.edit().clear().commit()
    }
}
