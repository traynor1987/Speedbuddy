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
      "cameraCorrections":[{"id":"node/123","source":"OSM","lat":53.1,"lon":-2.8,"type":"SPEED","direction":180,"note":null,"mph":20,"updated":124}],"suppressedCameraIds":["node/456"],
      "roadLimits":{"way/1":20},"roadCorrections":[],"cameraAliases":[],"junctions":[]}"""

    @Test fun legacyRestoreRetainsOriginalAndReexportsActiveMapOwnerData() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        val backup = OwnerBackupCodec.parse(legacy)
        val store = OwnerBackupStore(context)
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            store.restore(backup, cameras, roads, prefs)
            assertEquals(backup.cameras, cameras.userCameras())
            assertFalse(prefs.getBoolean("cameraSound", true))
            assertEquals(5, prefs.getInt("tolerance", 2))
            assertEquals(setOf("node/456"), cameras.suppressedCameraIds())
            assertEquals("node/123", cameras.cameraCorrections().single().id)
            assertEquals(20, cameras.roadLimit("way/1"))
        } }
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            assertEquals(legacy, OwnerBackupStore(context).legacyArchives().single())
            val exported = OwnerBackupCodec.export(cameras.userCameras(), prefs,
                corrections = cameras.cameraCorrections(), roadLimits = cameras.roadLimits(),
                suppressedCameraIds = cameras.suppressedCameraIds(), roadCorrections = cameras.roadCorrections(),
                aliases = cameras.aliasLinks(), junctions = JunctionStore(cameras).all(),
                roadOverrides = roads.overrides(), boundaries = roads.boundaries(), legacyArchives = store.legacyArchives())
            val roundTrip = OwnerBackupCodec.parse(exported)
            assertEquals(legacy, roundTrip.legacyArchives.single())
            assertEquals(setOf("node/456"), roundTrip.suppressedCameraIds)
            assertEquals("node/123", roundTrip.corrections.single().id)
            assertEquals("light", roundTrip.settings["theme"])
            assertTrue(roundTrip.cameras.single().bidirectional)
            assertTrue(roundTrip.archivedOnly.isEmpty())
        } }
        prefs.edit().clear().commit()
    }
    @Test fun roadCorrectionsRoundTripAndSurviveDatabaseReopen() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        val point = GeoPoint(53.1, -2.8)
        val boundary = BoundaryCorrection("way/1", "way/2", 30, 20, point, Geo.ahead(point, 90.0, 100.0), 90.0, 5.0, 5.0, .9, 2.0, 123)
        val text = RoadDb(context, "original.db").use { source ->
            source.setOverride("way/1", 90.0, 30); source.saveBoundary(boundary)
            OwnerBackupCodec.export(emptyList(), prefs, roadOverrides = source.overrides(), boundaries = source.boundaries())
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
    @Test fun fullOwnerBackupRestoresMapAndOfflineRecordsAndRetainsReceiptAcrossReopen() = isolated { context ->
        val prefs = context.getSharedPreferences("backup-${UUID.randomUUID()}", 0)
        prefs.edit().putString("theme", "dark").putBoolean("limitVoice", false).putBoolean("fixedCamera", false).commit()
        val point = GeoPoint(53.1, -2.8)
        val group = CameraJunction("junction:full", "Crossroads", point, 4)
        val owner = Camera("owner:full", point, CameraType.COMBINED, CameraSource.USER, 90.0, 30,
            "Both approaches", 123, bidirectional = true, junction = group)
        val correction = CameraCorrection("lufop:full", CameraSource.LUFOP, point, CameraType.SPEED,
            180.0, "Owner correction", 20, point, 124, true)
        val typed = RoadLimitCorrection("way/national", RoadLimitKind.NATIONAL_SINGLE, 60, "30 mph", 125)
        val directed = RoadDb.Override("way/directed", 90.0, 0, 30, point, 126, 5.0)
        val boundary = BoundaryCorrection("way/directed", "way/next", 30, 20, point,
            Geo.ahead(point, 90.0, 100.0), 90.0, 5.0, 5.0, .9, 2.0, 127)
        val text = OwnerBackupCodec.export(listOf(owner), prefs, corrections = listOf(correction),
            roadLimits = mapOf("way/numeric" to 40), suppressedCameraIds = setOf("node/hidden"),
            roadCorrections = listOf(typed), aliases = listOf("node/full" to "lufop:full"), junctions = listOf(group),
            roadOverrides = listOf(directed), boundaries = listOf(boundary))
        prefs.edit().clear().commit()
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            OwnerBackupStore(context).restore(OwnerBackupCodec.parse(text), cameras, roads, prefs)
        } }
        CameraDb(context).use { cameras -> RoadDb(context).use { roads ->
            assertEquals(owner, cameras.userCameras().single())
            assertEquals(correction, cameras.cameraCorrections().single())
            assertEquals(group, JunctionStore(cameras).all().single())
            assertEquals(setOf("node/hidden"), cameras.suppressedCameraIds())
            assertEquals(listOf("node/full" to "lufop:full"), cameras.aliasLinks())
            assertEquals(typed, cameras.roadCorrection("way/national")); assertEquals(40, cameras.roadLimit("way/numeric"))
            assertEquals(directed, roads.overrides().single()); assertEquals(boundary, roads.boundaries().single())
            assertEquals("dark", prefs.getString("theme", null)); assertFalse(prefs.getBoolean("limitVoice", true))
            assertFalse(prefs.getBoolean("fixedCamera", true))
            val receipt = File(context.filesDir, "owner-backups/restore-receipts").listFiles()!!.single()
            assertEquals(text, receipt.readText())
        } }
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
