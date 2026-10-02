package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class OwnerBackupMigrationTest {
    @Test fun legacyV8CameraRestoresFromPortableJson() {
        val backup = OwnerBackupCodec.parse(legacy)
        assertEquals("owner-one", backup.cameras.single().id)
        assertEquals(30, backup.cameras.single().enforcedMph)
        assertEquals(false, backup.settings["cameraSound"])
        assertEquals(5, backup.settings["tolerance"])
        assertEquals(legacy, backup.legacyArchives.single())
        assertTrue(backup.archivedOnly.containsAll(listOf("camera corrections", "suppressed cameras",
            "legacy speed-limit overrides", "bidirectional camera flags", "additional preview settings")))
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
        assertTrue(parsed.archivedOnly.contains("suppressed cameras"))
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
    private fun current() = org.json.JSONObject().put("format", "speed-buddy-owner-backup").put("version", 9)
        .put("cameras", org.json.JSONArray()).put("settings", org.json.JSONObject())
        .put("roadOverrides", org.json.JSONArray()).put("boundaries", org.json.JSONArray())
        .put("legacyArchives", org.json.JSONArray())
    companion object {
        val legacy = """{"format":"speed-buddy-owner-backup","version":8,
          "cameras":[{"id":"owner-one","lat":53.1,"lon":-2.8,"type":"SPEED","direction":90,
            "mph":30,"note":"Home road","updated":123,"bidirectional":true,"junctionId":null}],
          "settings":{"cameraSound":false,"tolerance":5,"theme":"dark","mobileLifetime":120},
          "cameraCorrections":[{"id":"node/123","direction":180}],"roadLimits":{"way/456":20},
          "suppressedCameraIds":["node/789"],"roadCorrections":[],"cameraAliases":[],"junctions":[]}"""
    }
}
