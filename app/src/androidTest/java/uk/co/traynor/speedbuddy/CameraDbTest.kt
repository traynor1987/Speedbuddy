package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDbTest {
    @Test fun importedLayerReplacesAtomicallyAndPreservesPersonalCameras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CameraDb(context).use { db ->
            val owner = db.create(GeoPoint(53.0, -2.0), CameraType.SPEED)
            try {
                val first = Camera("lufop:one", GeoPoint(53.005, -2.0), CameraType.RED_LIGHT, CameraSource.LUFOP)
                assertEquals(1, db.replaceImported(listOf(first), "2026-09-01"))
                assertEquals(first, db.importedNearby(first.point).single())
                assertEquals(1, db.importedInfo()?.count)
                assertThrows(IllegalArgumentException::class.java) { db.replaceImported(emptyList(), "bad") }
                assertEquals(first, db.importedNearby(first.point).single())
                val second = first.copy(id = "lufop:two", point = GeoPoint(53.01, -2.0))
                db.replaceImported(listOf(second), "2026-10-01")
                assertEquals(listOf(second), db.importedNearby(first.point))
                assertTrue(db.userCameras().any { it.id == owner.id })
            } finally {
                db.delete(owner.id)
                db.writableDatabase.delete("imported_cameras", null, null)
                db.writableDatabase.delete("imported_info", null, null)
            }
        }
    }
    @Test fun userCameraPersistsAndIsDetectedAfterReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = CameraDb(context).use { db -> db.create(GeoPoint(53.005, -2.0), CameraType.RED_LIGHT).id }
        CameraDb(context).use { db ->
            val camera = db.userCameras().first { it.id == id }
            val fix = Fix(GeoPoint(53.002, -2.0), 5.0, 12.5, 1.0, 0.0, 1000)
            val (alert, _) = CameraApproachDetector().evaluate(fix, null, listOf(camera), 28.0)
            assertEquals(id, alert?.camera?.id)
            db.delete(id)
        }
    }
    @Test fun backupRoundTripMergesUserCamerasAndSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("backup-test", 0)
        prefs.edit().putBoolean("cameraSound", false).putInt("tolerance", 5).commit()
        val camera = Camera("backup-fixture", GeoPoint(53.005, -2.0), CameraType.SPEED, CameraSource.USER,
            0.0, 30, "Test camera", 1234L)
        val json = OwnerBackupCodec.export(listOf(camera), prefs)
        val parsed = OwnerBackupCodec.parse(json)
        assertEquals(camera, parsed.cameras.single())
        CameraDb(context).use { db ->
            try {
                db.merge(parsed.cameras)
                assertEquals(camera, db.userCameras().first { it.id == camera.id })
                OwnerBackupCodec.applySettings(parsed.settings, prefs)
                assertFalse(prefs.getBoolean("cameraSound", true))
                assertEquals(5, prefs.getInt("tolerance", 2))
            } finally { db.delete(camera.id); prefs.edit().clear().commit() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnerBackupCodec.parse(json.replace("\"lat\": 53.005", "\"lat\": 999"))
        }
    }
}
