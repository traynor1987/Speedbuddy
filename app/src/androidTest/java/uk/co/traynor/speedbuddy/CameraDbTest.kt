package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDbTest {
    @Test fun schemaTwoMigrationCreatesCorrectionsWithoutDuplicateColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
            database.execSQL("INSERT INTO cameras VALUES('owner-old',53.0,-2.0,'SPEED',NULL,30,'saved',123)")
            CameraDb(context).use { helper -> helper.onUpgrade(database, 2, 6) }
            database.rawQuery("SELECT note FROM cameras WHERE id='owner-old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("saved", c.getString(0))
            }
            database.rawQuery("PRAGMA table_info(road_limits)", null).use { c ->
                val columns = mutableSetOf<String>()
                while (c.moveToNext()) columns.add(c.getString(1))
                assertTrue(columns.containsAll(setOf("mph", "kind", "source_value", "updated")))
            }
        }
    }
    @Test fun schemaThreeMigrationRetainsCameraAndRoadCorrections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE camera_corrections(id TEXT PRIMARY KEY, source TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, note TEXT)")
            database.execSQL("CREATE TABLE road_limits(id TEXT PRIMARY KEY, mph INTEGER NOT NULL)")
            database.execSQL("INSERT INTO camera_corrections VALUES('lufop:old','LUFOP',53.0,-2.0,'SPEED',90.0,'kept')")
            database.execSQL("INSERT INTO road_limits VALUES('way/old',20)")
            CameraDb(context).use { helper -> helper.onUpgrade(database, 3, 6) }
            database.rawQuery("SELECT note,mph FROM camera_corrections WHERE id='lufop:old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("kept", c.getString(0)); assertTrue(c.isNull(1))
            }
            database.rawQuery("SELECT mph,kind FROM road_limits WHERE id='way/old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(20, c.getInt(0)); assertEquals("NUMERIC", c.getString(1))
            }
            database.rawQuery("SELECT COUNT(*) FROM suppressed_cameras", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
            database.rawQuery("SELECT COUNT(*) FROM import_status", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
        }
    }
    @Test fun suppressionAndCorrectionsSurviveSourceReplacementAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "lufop:persistence-test"
        val original = Camera(id, GeoPoint(53.0, -2.0), CameraType.SPEED, CameraSource.LUFOP)
        CameraDb(context).use { db ->
            db.saveCameraCorrection(CameraCorrection(id, CameraSource.LUFOP, GeoPoint(53.001, -2.0),
                CameraType.RED_LIGHT, 90.0, null, 20, original.point))
            assertEquals(20, db.effectiveCameras(listOf(original), emptyList()).single().enforcedMph)
            db.suppressCamera(id, CameraSource.LUFOP)
            assertTrue(db.effectiveCameras(listOf(original), emptyList()).isEmpty())
        }
        CameraDb(context).use { db ->
            try {
                assertTrue(db.effectiveCameras(listOf(original.copy(point = GeoPoint(53.0001, -2.0))), emptyList()).isEmpty())
                assertEquals(original.point, db.cameraCorrections().first { it.id == id }.sourcePoint)
            } finally { db.unsuppressCamera(id); db.deleteCameraCorrection(id) }
        }
    }
    @Test fun importCarriesCorrectionToUnambiguousMovedSourceCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Camera("lufop:move-old", GeoPoint(53.04, -2.0), CameraType.SPEED, CameraSource.LUFOP)
        val replacement = original.copy(id = "lufop:move-new", point = GeoPoint(53.04004, -2.0))
        CameraDb(context).use { db ->
            try {
                db.replaceImported(listOf(original), "first")
                db.saveCameraCorrection(CameraCorrection(original.id, CameraSource.LUFOP,
                    GeoPoint(53.0401, -2.0), CameraType.RED_LIGHT, 90.0, null, 20, original.point))
                db.suppressCamera(original.id, CameraSource.LUFOP)
                db.replaceImported(listOf(replacement), "second")
                assertTrue(replacement.id in db.suppressedCameraIds())
                assertEquals(20, db.cameraCorrections().first { it.id == replacement.id }.enforcedMph)
            } finally {
                db.deleteCameraCorrection(original.id); db.deleteCameraCorrection(replacement.id)
                db.unsuppressCamera(original.id); db.unsuppressCamera(replacement.id)
                db.writableDatabase.delete("imported_cameras", null, null)
                db.writableDatabase.delete("imported_info", null, null)
            }
        }
    }
    @Test fun resettingOverridesKeepsOwnerCamerasAndImportedSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CameraDb(context).use { db ->
            val owner = db.create(GeoPoint(53.02, -2.0), CameraType.SPEED)
            val source = Camera("lufop:reset-test", GeoPoint(53.02, -2.0), CameraType.SPEED, CameraSource.LUFOP)
            try {
                db.replaceImported(listOf(source), "test")
                db.suppressCamera(source.id, CameraSource.LUFOP)
                db.saveRoadLimit("way/reset-test", 20)
                db.resetCameraOverrides(); db.resetRoadLimits()
                assertTrue(db.suppressedCameraIds().isEmpty())
                assertNull(db.roadLimit("way/reset-test"))
                assertTrue(db.userCameras().any { it.id == owner.id })
                assertEquals(source, db.importedNearby(source.point).single { it.id == source.id })
            } finally {
                db.delete(owner.id); db.writableDatabase.delete("imported_cameras", "id=?", arrayOf(source.id))
                db.writableDatabase.delete("imported_info", null, null)
            }
        }
    }
    @Test fun localCorrectionsPersistAndApplyToImportedCameraAndRoad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Camera("lufop:correction-test", GeoPoint(53.0, -2.0), CameraType.SPEED, CameraSource.LUFOP)
        val correction = CameraCorrection(original.id, CameraSource.LUFOP, GeoPoint(53.0001, -2.0), CameraType.RED_LIGHT, 90.0, "Eastbound")
        CameraDb(context).use { db ->
            try {
                db.saveCameraCorrection(correction)
                db.saveRoadLimit("way/correction-test", 20)
                val resolved = db.applyCameraCorrections(listOf(original)).single()
                assertEquals(correction.point, resolved.point)
                assertEquals(CameraType.RED_LIGHT, resolved.type)
                assertEquals(90.0, resolved.direction!!, 0.001)
                assertEquals(20, db.roadLimit("way/correction-test"))
            } finally {
                db.deleteCameraCorrection(original.id)
                db.deleteRoadLimit("way/correction-test")
            }
        }
        CameraDb(context).use { db ->
            assertEquals(original, db.applyCameraCorrections(listOf(original)).single())
            assertNull(db.roadLimit("way/correction-test"))
        }
    }
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
                db.recordImportFailure("Invalid archive")
                assertEquals(first, db.importedNearby(first.point).single())
                assertEquals("Invalid archive", db.importStatus()?.failure)
                val second = first.copy(id = "lufop:two", point = GeoPoint(53.01, -2.0))
                db.replaceImported(listOf(second), "2026-10-01")
                assertNull(db.importStatus()?.failure)
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
    @Test fun backupIncludesLocalMapCorrectionsAndAcceptsOlderVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("map-backup-test", 0)
        val correction = CameraCorrection("lufop:test", CameraSource.LUFOP,
            GeoPoint(53.0, -2.0), CameraType.RED_LIGHT, 180.0, "Southbound")
        val json = OwnerBackupCodec.export(emptyList(), prefs, listOf(correction), mapOf("way/123" to 20))
        val restored = OwnerBackupCodec.parse(json)
        assertEquals(correction, restored.corrections.single())
        assertEquals(20, restored.roadLimits["way/123"])
        val old = OwnerBackupCodec.export(emptyList(), prefs)
        assertTrue(OwnerBackupCodec.parse(old).corrections.isEmpty())
        prefs.edit().clear().commit()
    }
    @Test fun ownerBackupKeepsSuppressionAndCorrectedSpeed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("map-backup-v3-test", 0)
        val correction = CameraCorrection("lufop:test", CameraSource.LUFOP,
            GeoPoint(53.0, -2.0), CameraType.SPEED, 90.0, null, 20,
            GeoPoint(53.001, -2.001), 1234L)
        val parsed = OwnerBackupCodec.parse(OwnerBackupCodec.export(emptyList(), prefs,
            listOf(correction), emptyMap(), setOf(correction.id)))
        assertEquals(correction, parsed.corrections.single())
        assertEquals(setOf(correction.id), parsed.suppressedCameraIds)
        prefs.edit().clear().commit()
    }
    @Test fun ownerBackupRetainsNationalAndUnknownRoadSemantics() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("road-backup-test", 0)
        val items = listOf(RoadLimitCorrection("way/41", RoadLimitKind.NATIONAL_SINGLE, 60, "30 mph", 42),
            RoadLimitCorrection("way/42", RoadLimitKind.UNKNOWN, null, "50 mph", 43))
        val parsed = OwnerBackupCodec.parse(OwnerBackupCodec.export(emptyList(), prefs,
            roadCorrections = items))
        assertEquals(items, parsed.roadCorrections)
        prefs.edit().clear().commit()
    }
}
