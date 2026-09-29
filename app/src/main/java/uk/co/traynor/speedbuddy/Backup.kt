package uk.co.traynor.speedbuddy

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class OwnerBackup(val cameras: List<Camera>, val settings: Map<String, Any>)

/** A portable, versioned owner export. No route history or public OSM database is included. */
object OwnerBackupCodec {
    private val booleans = mapOf("overspeed" to false, "speedCamera" to true, "redCamera" to true,
        "cameraSound" to true, "vibrate" to true)
    fun export(cameras: List<Camera>, prefs: SharedPreferences): String {
        val records = JSONArray()
        cameras.filter { it.source == CameraSource.USER }.forEach { camera ->
            records.put(JSONObject().put("id", camera.id).put("lat", camera.point.lat).put("lon", camera.point.lon)
                .put("type", camera.type.name).put("direction", camera.direction ?: JSONObject.NULL)
                .put("mph", camera.enforcedMph ?: JSONObject.NULL).put("note", camera.note ?: JSONObject.NULL)
                .put("updated", camera.updatedAtMs))
        }
        val settings = JSONObject()
        booleans.forEach { (key, default) -> settings.put(key, prefs.getBoolean(key, default)) }
        settings.put("tolerance", prefs.getInt("tolerance", 2))
        return JSONObject().put("format", "speed-buddy-owner-backup").put("version", 1)
            .put("cameras", records).put("settings", settings).toString(2)
    }

    fun parse(text: String): OwnerBackup {
        require(text.length <= 2_000_000) { "Backup is too large" }
        val root = JSONObject(text)
        require(root.getString("format") == "speed-buddy-owner-backup" && root.getInt("version") == 1) {
            "Unsupported Speed Buddy backup"
        }
        val records = root.getJSONArray("cameras")
        require(records.length() <= 10_000) { "Too many camera records" }
        val cameras = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            val id = item.getString("id")
            val lat = item.getDouble("lat"); val lon = item.getDouble("lon")
            val direction = if (item.isNull("direction")) null else item.getDouble("direction")
            val mph = if (item.isNull("mph")) null else item.getInt("mph")
            val note = if (item.isNull("note")) null else item.getString("note")
            require(id.isNotBlank() && id.length <= 100 && lat.isFinite() && lat in -90.0..90.0 &&
                lon.isFinite() && lon in -180.0..180.0 && (direction == null || direction.isFinite() && direction in 0.0..359.0) &&
                (mph == null || mph in 5..130) && (note == null || note.length <= 100)) { "Invalid camera record" }
            Camera(id, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")), CameraSource.USER,
                direction, mph, note, item.getLong("updated"))
        }
        require(cameras.map { it.id }.distinct().size == cameras.size) { "Duplicate camera IDs" }
        val source = root.getJSONObject("settings")
        val settings = buildMap<String, Any> {
            booleans.forEach { (key, _) -> if (source.has(key)) {
                require(source.get(key) is Boolean) { "Invalid setting: $key" }
                put(key, source.getBoolean(key))
            } }
            if (source.has("tolerance")) {
                val value = source.getInt("tolerance")
                require(value in listOf(0, 1, 2, 3, 5)) { "Invalid warning threshold" }
                put("tolerance", value)
            }
        }
        return OwnerBackup(cameras, settings)
    }

    fun applySettings(settings: Map<String, Any>, prefs: SharedPreferences) {
        val editor = prefs.edit()
        settings.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        } }
        check(editor.commit()) { "Could not save settings" }
    }
}
