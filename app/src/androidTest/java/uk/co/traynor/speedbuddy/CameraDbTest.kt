package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDbTest {
    @Before fun createIsolatedTestDatabase() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("speedbuddy-tests.db") }
    @After fun removeIsolatedTestDatabase() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("speedbuddy-tests.db") }
    @Test fun bundledUkCameraLayerIsCompleteAndDoesNotInventBearings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batch = context.assets.open("lufop-uk-2026-09.zip").use(LufopAscImporter::inspect)
        assertEquals(5_233, batch.cameras.size)
        assertEquals(4_220, batch.cameras.count { it.type == CameraType.SPEED })
        assertEquals(1_013, batch.cameras.count { it.type == CameraType.RED_LIGHT })
        assertTrue(batch.cameras.all { it.direction == null && it.enforcedMph == null })
    }
    @Test fun schemaTwoMigrationCreatesCorrectionsWithoutDuplicateColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
            database.execSQL("INSERT INTO cameras VALUES('owner-old',53.0,-2.0,'SPEED',NULL,30,'saved',123)")
            CameraDb(context,"speedbuddy-tests.db").use { helper -> helper.onUpgrade(database, 2, 8) }
            database.rawQuery("SELECT note FROM cameras WHERE id='owner-old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("saved", c.getString(0))
            }
            database.rawQuery("PRAGMA table_info(road_limits)", null).use { c ->
                val columns = mutableSetOf<String>()
                while (c.moveToNext()) columns.add(c.getString(1))
                assertTrue(columns.containsAll(setOf("mph", "kind", "source_value", "updated")))
            }
            database.rawQuery("SELECT bidirectional FROM cameras WHERE id='owner-old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
        }
    }
    @Test fun schemaThreeMigrationRetainsCameraAndRoadCorrections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE camera_corrections(id TEXT PRIMARY KEY, source TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, note TEXT)")
            database.execSQL("CREATE TABLE road_limits(id TEXT PRIMARY KEY, mph INTEGER NOT NULL)")
            database.execSQL("INSERT INTO camera_corrections VALUES('lufop:old','LUFOP',53.0,-2.0,'SPEED',90.0,'kept')")
            database.execSQL("INSERT INTO road_limits VALUES('way/old',20)")
            CameraDb(context,"speedbuddy-tests.db").use { helper -> helper.onUpgrade(database, 3, 8) }
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
    @Test fun schemaSixMigrationRetainsOwnerAndCorrectionDirection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE cameras(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, mph INTEGER, note TEXT, updated INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE camera_corrections(id TEXT PRIMARY KEY, source TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, type TEXT NOT NULL, direction REAL, note TEXT, mph INTEGER, source_lat REAL, source_lon REAL, updated INTEGER NOT NULL DEFAULT 0)")
            database.execSQL("INSERT INTO cameras VALUES('owner',53,-2,'SPEED',90,30,NULL,123)")
            database.execSQL("INSERT INTO camera_corrections VALUES('lufop:old','LUFOP',53,-2,'SPEED',180,NULL,NULL,53,-2,124)")
            CameraDb(context,"speedbuddy-tests.db").use { it.onUpgrade(database, 6, 8) }
            database.rawQuery("SELECT direction,bidirectional FROM cameras WHERE id='owner'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(90.0, c.getDouble(0), 0.0); assertEquals(0, c.getInt(1))
            }
            database.rawQuery("SELECT direction,bidirectional FROM camera_corrections WHERE id='lufop:old'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(180.0, c.getDouble(0), 0.0); assertEquals(0, c.getInt(1))
            }
        }
    }
    @Test fun suppressionAndCorrectionsSurviveSourceReplacementAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "lufop:persistence-test"
        val original = Camera(id, GeoPoint(53.0, -2.0), CameraType.SPEED, CameraSource.LUFOP)
        CameraDb(context,"speedbuddy-tests.db").use { db ->
            db.saveCameraCorrection(CameraCorrection(id, CameraSource.LUFOP, GeoPoint(53.001, -2.0),
                CameraType.RED_LIGHT, 90.0, null, 20, original.point))
            assertEquals(20, db.effectiveCameras(listOf(original), emptyList()).single().enforcedMph)
            db.suppressCamera(id, CameraSource.LUFOP)
            assertTrue(db.effectiveCameras(listOf(original), emptyList()).isEmpty())
        }
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
        CameraDb(context,"speedbuddy-tests.db").use { db ->
            assertEquals(original, db.applyCameraCorrections(listOf(original)).single())
            assertNull(db.roadLimit("way/correction-test"))
        }
    }
    @Test fun importedLayerReplacesAtomicallyAndPreservesPersonalCameras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
        val id = CameraDb(context,"speedbuddy-tests.db").use { db -> db.create(GeoPoint(53.005, -2.0), CameraType.RED_LIGHT).id }
        CameraDb(context,"speedbuddy-tests.db").use { db ->
            val camera = db.userCameras().first { it.id == id }
            val fix = Fix(GeoPoint(53.003, -2.0), 5.0, 12.5, 1.0, 0.0, 1000)
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
        CameraDb(context,"speedbuddy-tests.db").use { db ->
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
    @Test fun bidirectionalCameraAndOverrideSurviveDatabaseAndBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("both-backup-test", 0)
        val owner = Camera("both-owner-test", GeoPoint(53.0, -2.0), CameraType.SPEED,
            CameraSource.USER, 90.0, bidirectional = true)
        val override = CameraCorrection("lufop:both-test", CameraSource.LUFOP,
            GeoPoint(53.01, -2.0), CameraType.SPEED, 0.0, null, bidirectional = true)
        CameraDb(context,"speedbuddy-tests.db").use { db ->
            try {
                db.upsert(owner); db.saveCameraCorrection(override)
                assertTrue(db.userCameras().single { it.id == owner.id }.bidirectional)
                assertTrue(db.cameraCorrections().single { it.id == override.id }.bidirectional)
                val restored = OwnerBackupCodec.parse(OwnerBackupCodec.export(listOf(owner), prefs,
                    listOf(override)))
                assertTrue(restored.cameras.single().bidirectional)
                assertTrue(restored.corrections.single().bidirectional)
            } finally { db.delete(owner.id); db.deleteCameraCorrection(override.id); prefs.edit().clear().commit() }
        }
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
    @Test fun versionSevenMigrationKeepsOwnerDataAndAddsAliases() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val owner=CameraDb(context,"speedbuddy-tests.db").use { db->
            val camera=db.create(GeoPoint(53.0,-2.0),CameraType.COMBINED,359.9,30,"Owner",true)
            db.writableDatabase.execSQL("DROP TABLE camera_aliases")
            db.writableDatabase.execSQL("DROP INDEX owner_location")
            db.writableDatabase.version=7
            camera
        }
        CameraDb(context,"speedbuddy-tests.db").use { db->
            assertEquals(owner.id,db.userCameras().single().id)
            assertTrue(db.userCameras().single().bidirectional)
            assertTrue(db.aliasLinks().isEmpty())
            assertEquals(8,db.readableDatabase.version)
        }
    }
    @Test fun ownerRestoreRollsBackAllDatabaseLayersOnFailure() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("rollback-test",0)
        prefs.edit().putBoolean("cameraSound",true).commit()
        CameraDb(context,"speedbuddy-tests.db").use { db->
            val camera=Camera("rollback-owner",GeoPoint(53.0,-2.0),CameraType.SPEED,CameraSource.USER)
            val invalid=CameraCorrection("invalid",CameraSource.USER,camera.point,camera.type,null,null)
            val backup=OwnerBackup(listOf(camera),mapOf("cameraSound" to false),listOf(invalid))
            assertThrows(IllegalArgumentException::class.java) { db.restoreOwnerData(backup,prefs) }
            assertTrue(db.userCameras().isEmpty());assertTrue(db.cameraCorrections().isEmpty())
            assertTrue(prefs.getBoolean("cameraSound",false))
        }
        prefs.edit().clear().commit()
    }
    @Test fun boundedOwnerQueryHandlesThousandsWithoutReturningDistantRecords() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        CameraDb(context,"speedbuddy-tests.db").use { db->
            db.writableDatabase.beginTransaction()
            try {
                repeat(3000) { i->db.upsert(Camera("distant-$i",GeoPoint(55.0+i*.000001,-3.0),CameraType.SPEED,CameraSource.USER)) }
                db.create(GeoPoint(53.0,-2.0),CameraType.SPEED)
                db.writableDatabase.setTransactionSuccessful()
            } finally { db.writableDatabase.endTransaction() }
            assertEquals(3001,db.ownerCameraCount())
            assertEquals(1,db.effectiveInBounds(52.99,-2.01,53.01,-1.99,emptyList()).size)
        }
    }
    @Test fun linkedCameraHideAndRestoreAffectBothSourcesAfterReopen() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val lufop=Camera("lufop:linked",GeoPoint(53.0,-2.0),CameraType.SPEED,CameraSource.LUFOP)
        val osm=lufop.copy(id="node/42",source=CameraSource.OSM)
        CameraDb(context,"speedbuddy-tests.db").use { db->
            db.replaceImported(listOf(lufop),"fixture")
            val effective=db.effectiveInBounds(52.99,-2.01,53.01,-1.99,listOf(osm)).single()
            db.hideEffectiveCamera(effective)
        }
        CameraDb(context,"speedbuddy-tests.db").use { db->
            assertTrue(db.effectiveInBounds(52.99,-2.01,53.01,-1.99,listOf(osm)).isEmpty())
            db.restoreHiddenCamera(osm.id)
            assertEquals(1,db.effectiveInBounds(52.99,-2.01,53.01,-1.99,listOf(osm)).size)
        }
    }
    @Test fun cachedGeometryAndCenterRemainTogetherAcrossRegionsAndReopen() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="isolated-road-cache.db";context.deleteDatabase(name)
        try {
            val now=System.currentTimeMillis()
            RoadCache(context,name).use { cache->
                cache.save(CachedRoadRegion(GeoPoint(53.0,-2.0),now,"first"))
                cache.save(CachedRoadRegion(GeoPoint(54.0,-3.0),now+1,"second"))
            }
            RoadCache(context,name).use { cache->
                assertEquals("first",cache.latest(GeoPoint(53.0,-2.0))?.json)
                assertEquals(2,cache.diagnostics().first)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun osmParserSkipsMalformedRecordsAndDoesNotUseRawFacingDirection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val json=org.json.JSONObject("""{"elements":[{"type":"node","id":1,"lat":999,"lon":-2,"tags":{"highway":"speed_camera"}},{"type":"node","id":2,"lat":53.0,"lon":-2.0,"tags":{"highway":"speed_camera","direction":"180"}}]}""")
        val snapshot=OsmDataSource(context).decode(json,1234,GeoPoint(53.0,-2.0))
        assertEquals("node/2",snapshot.cameras.single().id);assertNull(snapshot.cameras.single().direction)
    }

    @Test fun roadCacheReadsRegionsLargerThanAndroidCursorWindow() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="large-road-cache-test.db";context.deleteDatabase(name)
        try { RoadCache(context,name).use { cache->
            val data="x".repeat(3_000_000)
            cache.save(CachedRoadRegion(GeoPoint(53.0,-2.0),System.currentTimeMillis(),data))
            assertEquals(data,cache.latest(GeoPoint(53.0,-2.0))?.json)
        } } finally { context.deleteDatabase(name) }
    }

}
