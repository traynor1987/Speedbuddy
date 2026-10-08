package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class OwnerBackupMigrationTest {
    @Test fun sharedCorrectionExportRestoresAlongsideOldDirectionalRecords() {
        val root=current().put("version",13).put("boundaryObservations",org.json.JSONArray())
            .put("junctions",org.json.JSONArray()).put("cameraCorrections",org.json.JSONArray())
            .put("roadLimits",org.json.JSONObject()).put("suppressedCameraIds",org.json.JSONArray())
            .put("roadCorrections",org.json.JSONArray()).put("cameraAliases",org.json.JSONArray())
            .put("roadOverrides",org.json.JSONArray().put(RoadJson.override(RoadDb.Override("osm:123",0.0,20,sharedAcrossDirections=true)))
                .put(org.json.JSONObject().put("road","way/456").put("bearing",180).put("mph",50)))
        val rows=OwnerBackupCodec.parse(root.toString()).roadOverrides
        assertTrue(rows[0].sharedAcrossDirections);assertFalse(rows[1].sharedAcrossDirections)
        assertEquals("osm:123",rows[0].road)
        assertEquals(20,RoadDb.selectOverride(rows,"way/123",180.0))
        assertNull(RoadDb.selectOverride(rows,"way/456",0.0))
        root.getJSONArray("roadOverrides").getJSONObject(0).put("shared","yes")
        assertThrows(IllegalArgumentException::class.java) { OwnerBackupCodec.parse(root.toString()) }
    }
    @Test fun legacyV8RestoresMapOwnerFieldsFromPortableJson() {
        val backup = OwnerBackupCodec.parse(legacy)
        assertEquals("owner-one", backup.cameras.single().id)
        assertEquals(30, backup.cameras.single().enforcedMph)
        assertEquals(false, backup.settings["cameraSound"])
        assertEquals(5, backup.settings["tolerance"])
        assertEquals(legacy, backup.legacyArchives.single())
        assertTrue(backup.cameras.single().bidirectional)
        assertEquals("dark", backup.settings["theme"])
        assertEquals("node/123", backup.corrections.single().id)
        assertEquals(20, backup.roadLimits["way/456"])
        assertEquals(setOf("node/789"), backup.suppressedCameraIds)
        assertTrue(backup.archivedOnly.isEmpty())
    }
    @Test fun futureBackupVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OwnerBackupCodec.parse(legacy.replace("\"version\":8", "\"version\":100"))
        }
    }
    @Test fun currentBackupPreservesOriginalLegacyText() {
        val root = current().put("legacyArchives", org.json.JSONArray().put(legacy))
        val parsed = OwnerBackupCodec.parse(root.toString())
        assertEquals(legacy, parsed.legacyArchives.single())
        assertEquals(setOf("node/789"), parsed.suppressedCameraIds)
        assertEquals("dark", parsed.settings["theme"])
        assertTrue(parsed.archivedOnly.isEmpty())
    }
    @Test fun offlineV10RetainedMapSettingsBecomeActive() {
        val root = current().put("version", 10).put("legacyArchives", org.json.JSONArray().put(legacy))
        assertEquals("dark", OwnerBackupCodec.parse(root.toString()).settings["theme"])
    }
    @Test fun invalidRoadOverrideIsRejectedBeforeRestore() {
        val root = current()
            .put("roadOverrides", org.json.JSONArray().put(org.json.JSONObject()
                .put("road", "way/1").put("bearing", 400).put("mph", 30)))
            .put("boundaries", org.json.JSONArray()).put("legacyArchives", org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java) { OwnerBackupCodec.parse(root.toString()) }
    }
    @Test fun currentVersionWithUnknownFieldsIsRejectedInsteadOfDropped() {
        assertThrows(IllegalArgumentException::class.java) {
            OwnerBackupCodec.parse(current().put("unrecognizedOwnerRecords", org.json.JSONArray().put("precious")).toString())
        }
    }
    @Test fun newCorrectionEvidenceAndUnknownSelectionRestoreFromV10() {
        val root=current().put("version",10).put("roadOverrides",org.json.JSONArray().put(org.json.JSONObject()
            .put("road","way/2").put("bearing",0).put("mph",0).put("source",30)
            .put("point",org.json.JSONArray().put(53.0).put(-2.0)).put("at",1234).put("accuracy",5)))
        val row=OwnerBackupCodec.parse(root.toString()).roadOverrides.single()
        assertEquals(0,row.mph);assertEquals(30,row.sourceMph);assertEquals(GeoPoint(53.0,-2.0),row.point)
        assertEquals(1234L,row.recordedAt);assertEquals(5.0,row.accuracy!!,0.0)
    }
    @Test fun fullV11CarriesBothOwnerLineagesAndGivesCurrentRecordsPriority() {
        val root = org.json.JSONObject(legacy).put("version", 11)
            .put("roadOverrides", org.json.JSONArray().put(org.json.JSONObject()
                .put("road", "way/2").put("bearing", 90).put("mph", 0)
                .put("source", 30).put("point", org.json.JSONArray().put(53.1).put(-2.8)).put("at", 1234).put("accuracy", 5)))
            .put("boundaries", org.json.JSONArray().put(org.json.JSONObject()
                .put("from", "way/2").put("to", "way/3").put("old", 30).put("new", 20)
                .put("predicted", org.json.JSONArray().put(53.1).put(-2.8))
                .put("observed", org.json.JSONArray().put(53.101).put(-2.8))
                .put("bearing", 90).put("pa", 5).put("oa", 5).put("confidence", .9).put("distance", 2).put("at", 1234)))
            .put("legacyArchives", org.json.JSONArray().put(legacy))
        root.getJSONObject("settings").put("theme", "light")
        root.getJSONArray("cameras").getJSONObject(0).put("mph", 40).put("bidirectional", false)
        root.getJSONObject("roadLimits").put("way/456", 45)
        val parsed = OwnerBackupCodec.parse(root.toString())
        assertEquals("light", parsed.settings["theme"])
        assertEquals(40, parsed.cameras.single().enforcedMph); assertFalse(parsed.cameras.single().bidirectional)
        assertEquals(45, parsed.roadLimits["way/456"])
        assertEquals("node/123", parsed.corrections.single().id)
        assertEquals(setOf("node/789"), parsed.suppressedCameraIds)
        assertEquals(0, parsed.roadOverrides.single().mph); assertEquals(30, parsed.roadOverrides.single().sourceMph)
        assertEquals("way/3", parsed.boundaries.single().toId)
        assertEquals(legacy, parsed.legacyArchives.single()); assertEquals(root.toString(), parsed.sourceText)
    }
    @Test fun offlineCurrentCameraEditsTakePriorityWhileArchivedDirectionFlagsRecover() {
        val root = current().put("version", 10).put("legacyArchives", org.json.JSONArray().put(legacy))
        val camera = org.json.JSONObject(legacy).getJSONArray("cameras").getJSONObject(0)
        camera.remove("bidirectional"); camera.remove("junctionId"); camera.put("mph", 40)
        root.put("cameras", org.json.JSONArray().put(camera))
        root.getJSONObject("settings").put("cameraSound", true)
        val parsed = OwnerBackupCodec.parse(root.toString())
        assertEquals(40, parsed.cameras.single().enforcedMph)
        assertTrue(parsed.cameras.single().bidirectional); assertEquals(true, parsed.settings["cameraSound"])
        assertEquals("dark", parsed.settings["theme"])
    }
    @Test fun archiveActivationSelectsLatestTimestampForEachCameraAndCorrection() {
        val latest = org.json.JSONObject(legacy)
        latest.getJSONArray("cameras").getJSONObject(0).put("updated", 999).put("mph", 40)
        latest.getJSONArray("cameraCorrections").getJSONObject(0).put("updated", 999).put("mph", 50)
        val root = current().put("version", 10)
            .put("legacyArchives", org.json.JSONArray().put(legacy).put(latest.toString()))
        val parsed = OwnerBackupCodec.parse(root.toString())
        assertEquals(40, parsed.cameras.single().enforcedMph)
        assertEquals(50, parsed.corrections.single().enforcedMph)
        root.put("legacyArchives", org.json.JSONArray().put(latest.toString()).put(legacy))
        val reversed = OwnerBackupCodec.parse(root.toString())
        assertEquals(40, reversed.cameras.single().enforcedMph)
        assertEquals(50, reversed.corrections.single().enforcedMph)
    }
    @Test fun fullV11RetainedArchivesDoNotResurrectRemovedOwnerRecords() {
        val root = current().put("version", 11).put("legacyArchives", org.json.JSONArray().put(legacy))
            .put("cameraCorrections", org.json.JSONArray()).put("roadLimits", org.json.JSONObject())
            .put("suppressedCameraIds", org.json.JSONArray()).put("roadCorrections", org.json.JSONArray())
            .put("cameraAliases", org.json.JSONArray()).put("junctions", org.json.JSONArray())
        val parsed = OwnerBackupCodec.parse(root.toString())
        assertTrue(parsed.cameras.isEmpty()); assertTrue(parsed.corrections.isEmpty())
        assertTrue(parsed.roadLimits.isEmpty()); assertTrue(parsed.suppressedCameraIds.isEmpty())
        assertTrue(parsed.settings.isEmpty()); assertTrue(parsed.junctions.isEmpty())
        assertEquals(legacy, parsed.legacyArchives.single())
    }
    @Test fun fullCurrentCameraUnknownFieldsAndFractionalLimitsAreRejected() {
        val root = org.json.JSONObject(legacy).put("version", 11)
            .put("roadOverrides", org.json.JSONArray()).put("boundaries", org.json.JSONArray()).put("legacyArchives", org.json.JSONArray())
        root.getJSONArray("cameras").getJSONObject(0).put("futureOwnerField", "retain me")
        assertThrows(IllegalArgumentException::class.java) { OwnerBackupCodec.parse(root.toString()) }
        root.getJSONArray("cameras").getJSONObject(0).remove("futureOwnerField")
        root.getJSONArray("cameras").getJSONObject(0).put("mph", 30.5)
        assertThrows(IllegalArgumentException::class.java) { OwnerBackupCodec.parse(root.toString()) }
    }
    private fun current() = org.json.JSONObject().put("format", "speed-buddy-owner-backup").put("version", 9)
        .put("cameras", org.json.JSONArray()).put("settings", org.json.JSONObject())
        .put("roadOverrides", org.json.JSONArray()).put("boundaries", org.json.JSONArray())
        .put("legacyArchives", org.json.JSONArray())
    companion object {
        val legacy = """{"format":"speed-buddy-owner-backup","version":8,
          "cameras":[{"id":"owner-one","lat":53.1,"lon":-2.8,"type":"SPEED","direction":90,
            "mph":30,"note":"Home road","updated":123,"bidirectional":true,"junctionId":null}],
          "settings":{"cameraSound":false,"tolerance":5,"theme":"dark","mobileLifetime":120},
          "cameraCorrections":[{"id":"node/123","source":"OSM","lat":53.1,"lon":-2.8,"type":"SPEED","direction":180,"note":null,"mph":20,"updated":124}],"roadLimits":{"way/456":20},
          "suppressedCameraIds":["node/789"],"roadCorrections":[],"cameraAliases":[],"junctions":[]}"""
    }
}
