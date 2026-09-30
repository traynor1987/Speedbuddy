package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JunctionDbTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "junction-tests.db"
    private val group = CameraJunction("junction:test", "Fiveways", GeoPoint(53.0, -2.0), 5)
    @Before fun clear() { context.deleteDatabase(filename); context.getSharedPreferences("junction-tests",0).edit().clear().commit() }
    @After fun clean() { context.deleteDatabase(filename) }

    @Test fun versionNineMigrationPreservesOwnerImportedAndMobileLayers() {
        lateinit var camera: Camera
        CameraDb(context,filename).use { db ->
            camera = db.create(group.point, CameraType.SPEED, 0.0, 30)
            db.replaceImported(listOf(Camera("lufop:keep",group.point,CameraType.RED_LIGHT,CameraSource.LUFOP)),"test")
            val now = System.currentTimeMillis()
            MobileReportStore(db).record(MobileReport("mobile:keep",group.point,now,now,now+7_200_000,120,null,null,null))
            db.writableDatabase.execSQL("DROP TABLE junction_members")
            db.writableDatabase.execSQL("DROP TABLE camera_junctions")
            db.writableDatabase.version=9
        }
        CameraDb(context,filename).use { db ->
            assertEquals(10,db.readableDatabase.version)
            assertEquals(camera.id,db.userCameras().single().id)
            assertEquals(1,db.importedInfo()!!.count)
            assertEquals(1,MobileReportStore(db).activeInBounds(52.0,-3.0,54.0,-1.0).size)
            assertTrue(JunctionStore(db).all().isEmpty())
        }
    }

    @Test fun membersSurviveRestartMetadataEditsAndBoundedRepositoryReads() {
        CameraDb(context,filename).use { db ->
            JunctionStore(db).save(group)
            db.create(group.point,CameraType.COMBINED,0.0,30,junction=group)
            db.create(Geo.ahead(group.point,90.0,30.0),CameraType.RED_LIGHT,270.0,junction=group)
        }
        CameraDb(context,filename).use { db ->
            assertEquals(2,db.userCameras().count { it.junction==group })
            JunctionStore(db).save(group.copy(name="Fiveways updated",ways=4))
            val bounded=db.effectiveInBounds(52.0,-3.0,54.0,-1.0,emptyList())
            assertEquals(2,bounded.size);assertTrue(bounded.all { it.junction?.name=="Fiveways updated" && it.junction?.ways==4 })
            val member=bounded.first()
            db.upsert(member.copy(enforcedMph=20))
            assertEquals(group.id,db.userCameras().first { it.id==member.id }.junction?.id)
        }
    }

    @Test fun deletingMemberAndUngroupingPreserveRemainingCameras() {
        CameraDb(context,filename).use { db ->
            val store=JunctionStore(db);store.save(group)
            val a=db.create(group.point,CameraType.SPEED,0.0,30,junction=group)
            val b=db.create(group.point,CameraType.RED_LIGHT,180.0,junction=group)
            db.delete(a.id);assertEquals(group.id,db.userCameras().single().junction?.id)
            store.remove(group.id)
            assertEquals(b.id,db.userCameras().single().id);assertNull(db.userCameras().single().junction)
            assertTrue(store.all().isEmpty())
            db.readableDatabase.rawQuery("SELECT count(*) FROM junction_members",null).use { c -> c.moveToFirst();assertEquals(0,c.getInt(0)) }
        }
    }

    @Test fun invalidMemberOrCentreMoveRollsBackWithoutLosingExistingData() {
        CameraDb(context,filename).use { db ->
            val store=JunctionStore(db);store.save(group)
            val member=db.create(group.point,CameraType.COMBINED,0.0,30,junction=group)
            assertThrows(IllegalArgumentException::class.java) { db.upsert(member.copy(direction=null)) }
            assertEquals(0.0,db.userCameras().single().direction!!,0.0)
            assertThrows(IllegalArgumentException::class.java) { store.save(group.copy(point=Geo.ahead(group.point,0.0,400.0))) }
            assertEquals(group,store.find(group.id))
        }
    }

    @Test fun backupRoundTripKeepsEmptyGroupsAndMembershipsAndReadsLegacy() {
        val prefs=context.getSharedPreferences("junction-tests",0)
        lateinit var parsed: OwnerBackup
        CameraDb(context,filename).use { db ->
            JunctionStore(db).save(group);JunctionStore(db).save(group.copy(id="junction:empty",name="Empty",ways=4))
            db.create(group.point,CameraType.COMBINED,0.0,30,junction=group)
            val text=OwnerBackupCodec.export(db.userCameras(),prefs,junctions=JunctionStore(db).all())
            parsed=OwnerBackupCodec.parse(text)
            assertEquals(2,parsed.junctions.size);assertEquals(group.id,parsed.cameras.single().junction?.id)
            val legacy=JSONObject(text).put("version",7);legacy.remove("junctions")
            assertTrue(OwnerBackupCodec.parse(legacy.toString()).junctions.isEmpty())
            assertNull(OwnerBackupCodec.parse(legacy.toString()).cameras.single().junction)
            val dangling=JSONObject(text);dangling.getJSONArray("cameras").getJSONObject(0).put("junctionId","junction:missing")
            assertThrows(IllegalStateException::class.java) { OwnerBackupCodec.parse(dangling.toString()) }
            val duplicate=JSONObject(text);duplicate.getJSONArray("junctions").put(duplicate.getJSONArray("junctions").getJSONObject(0))
            assertThrows(IllegalArgumentException::class.java) { OwnerBackupCodec.parse(duplicate.toString()) }
        }
        context.deleteDatabase(filename)
        CameraDb(context,filename).use { db ->
            db.restoreOwnerData(parsed,prefs)
            assertEquals(2,JunctionStore(db).all().size);assertEquals(group,db.userCameras().single().junction)
        }
    }

    @Test fun invalidRestoreDoesNotLeaveGroupOrMemberBehind() {
        val prefs=context.getSharedPreferences("junction-tests",0)
        CameraDb(context,filename).use { db ->
            val member=Camera("invalid",group.point,CameraType.SPEED,CameraSource.USER,junction=group)
            assertThrows(IllegalArgumentException::class.java) {
                db.restoreOwnerData(OwnerBackup(listOf(member),emptyMap(),junctions=listOf(group)),prefs)
            }
            assertTrue(JunctionStore(db).all().isEmpty());assertTrue(db.userCameras().isEmpty())
        }
    }
}
