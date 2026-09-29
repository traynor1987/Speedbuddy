package uk.co.traynor.speedbuddy

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class OwnerBackup(val cameras: List<Camera>, val settings: Map<String, Any>,
    val corrections: List<CameraCorrection> = emptyList(), val roadLimits: Map<String, Int> = emptyMap(),
    val suppressedCameraIds: Set<String> = emptySet(),
    val roadCorrections: List<RoadLimitCorrection> = emptyList(),
    val aliases: List<Pair<String,String>> = emptyList())

/** A portable, versioned owner export. No route history or public OSM database is included. */
object OwnerBackupCodec {
    private val booleans = mapOf("overspeed" to false, "speedCamera" to true, "redCamera" to true,
        "cameraSound" to true, "vibrate" to true, "limitVoice" to true, "keepAwake" to true)
    fun export(cameras: List<Camera>, prefs: SharedPreferences,
        corrections: List<CameraCorrection> = emptyList(), roadLimits: Map<String, Int> = emptyMap(),
        suppressedCameraIds: Set<String> = emptySet(),
        roadCorrections: List<RoadLimitCorrection> = emptyList(), aliases: List<Pair<String,String>> = emptyList()): String {
        val records = JSONArray()
        cameras.filter { it.source == CameraSource.USER }.forEach { camera ->
            records.put(JSONObject().put("id", camera.id).put("lat", camera.point.lat).put("lon", camera.point.lon)
                .put("type", camera.type.name).put("direction", camera.direction ?: JSONObject.NULL)
                .put("mph", camera.enforcedMph ?: JSONObject.NULL).put("note", camera.note ?: JSONObject.NULL)
                .put("updated", camera.updatedAtMs).put("bidirectional", camera.bidirectional))
        }
        val settings = JSONObject()
        booleans.forEach { (key, default) -> settings.put(key, prefs.getBoolean(key, default)) }
        settings.put("tolerance", prefs.getInt("tolerance", 2))
        settings.put("theme",prefs.getString("theme","system"))
        val overrides = JSONArray()
        corrections.forEach { item -> overrides.put(JSONObject().put("id", item.id).put("source", item.source.name)
            .put("lat", item.point.lat).put("lon", item.point.lon).put("type", item.type.name)
            .put("direction", item.direction ?: JSONObject.NULL).put("note", item.note ?: JSONObject.NULL)
            .put("mph", item.enforcedMph ?: JSONObject.NULL)
            .put("sourceLat", item.sourcePoint?.lat ?: JSONObject.NULL)
            .put("sourceLon", item.sourcePoint?.lon ?: JSONObject.NULL)
            .put("updated", item.updatedAtMs).put("bidirectional", item.bidirectional)) }
        val limits = JSONObject()
        roadLimits.forEach { (id, mph) -> limits.put(id, mph) }
        val roadRecords = JSONArray()
        roadCorrections.forEach { item -> roadRecords.put(JSONObject().put("id", item.id)
            .put("kind", item.kind.name).put("mph", item.mph ?: JSONObject.NULL)
            .put("sourceValue", item.sourceValue ?: JSONObject.NULL).put("updated", item.updatedAtMs)) }
        return JSONObject().put("format", "speed-buddy-owner-backup").put("version", 6)
            .put("cameras", records).put("settings", settings)
            .put("cameraCorrections", overrides).put("roadLimits", limits)
            .put("suppressedCameraIds", JSONArray(suppressedCameraIds.sorted()))
            .put("roadCorrections", roadRecords)
            .put("cameraAliases",JSONArray(aliases.map { JSONArray(listOf(it.first,it.second)) })).toString(2)
    }

    fun parse(text: String): OwnerBackup {
        require(text.length <= 2_000_000) { "Backup is too large" }
        val root = JSONObject(text)
        require(root.getString("format") == "speed-buddy-owner-backup" && root.getInt("version") in 1..6) {
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
            val both = item.optBoolean("bidirectional", false)
            require(id.isNotBlank() && id.length <= 100 && lat.isFinite() && lat in -90.0..90.0 &&
                lon.isFinite() && lon in -180.0..180.0 && (direction == null || direction.isFinite() && direction >= 0.0 && direction < 360.0) &&
                (mph == null || mph in 5..130) && (note == null || note.length <= 100) &&
                (!both || direction != null)) { "Invalid camera record" }
            Camera(id, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")), CameraSource.USER,
                direction, mph, note, item.getLong("updated"), both)
        }
        require(cameras.map { it.id }.distinct().size == cameras.size) { "Duplicate camera IDs" }
        val source = root.getJSONObject("settings")
        val settings = buildMap<String, Any> {
            booleans.forEach { (key, _) -> if (source.has(key)) {
                require(source.get(key) is Boolean) { "Invalid setting: $key" }
                put(key, source.getBoolean(key))
            } }
            if(source.has("theme")) {
                val theme=source.getString("theme");require(theme in listOf("system","light","dark")) { "Invalid appearance" };put("theme",theme)
            }
            if (source.has("tolerance")) {
                val value = source.getInt("tolerance")
                require(value in listOf(0, 1, 2, 3, 5)) { "Invalid warning threshold" }
                put("tolerance", value)
            }
        }
        val corrections = if (root.getInt("version") == 1) emptyList() else {
            val array = root.getJSONArray("cameraCorrections")
            require(array.length() <= 10_000) { "Too many camera corrections" }
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val id = item.getString("id"); val sourceType = CameraSource.valueOf(item.getString("source"))
                val lat = item.getDouble("lat"); val lon = item.getDouble("lon")
                val direction = if (item.isNull("direction")) null else item.getDouble("direction")
                val note = if (item.isNull("note")) null else item.getString("note")
                val mph = if (!item.has("mph") || item.isNull("mph")) null else item.getInt("mph")
                val sourcePoint = if (item.has("sourceLat") && item.has("sourceLon") &&
                    !item.isNull("sourceLat") && !item.isNull("sourceLon"))
                    GeoPoint(item.getDouble("sourceLat"), item.getDouble("sourceLon")) else null
                val updated = item.optLong("updated", 0L)
                val both = item.optBoolean("bidirectional", false)
                require(id.isNotBlank() && id.length <= 100 && sourceType != CameraSource.USER &&
                    lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
                    (direction == null || direction.isFinite() && direction >= 0.0 && direction < 360.0) &&
                    (mph == null || mph in 5..130) && (sourcePoint == null || sourcePoint.lat.isFinite() &&
                        sourcePoint.lat in -90.0..90.0 && sourcePoint.lon.isFinite() && sourcePoint.lon in -180.0..180.0) &&
                    (note == null || note.length <= 100) && (!both || direction != null)) { "Invalid camera correction" }
                CameraCorrection(id, sourceType, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")),
                    direction, note, mph, sourcePoint, updated, both)
            }.also { require(it.map(CameraCorrection::id).distinct().size == it.size) { "Duplicate camera corrections" } }
        }
        val roadLimits = if (root.getInt("version") == 1) emptyMap() else root.getJSONObject("roadLimits").let { limits ->
            require(limits.length() <= 10_000) { "Too many road corrections" }
            limits.keys().asSequence().associateWith { id ->
                val mph = limits.getInt(id)
                require(id.startsWith("way/") && id.length <= 100 && mph in 5..130) { "Invalid road limit correction" }
                mph
            }
        }
        val hidden = if (root.getInt("version") < 3) emptySet() else root.getJSONArray("suppressedCameraIds").let { a ->
            require(a.length() <= 10_000) { "Too many hidden cameras" }
            (0 until a.length()).map(a::getString).onEach {
                require(it.length <= 100 && (it.startsWith("lufop:") || it.startsWith("node/"))) { "Invalid hidden camera" }
            }.toSet().also { require(it.size == a.length()) { "Duplicate hidden camera" } }
        }
        val roadCorrections = if (root.getInt("version") < 4) emptyList() else root.getJSONArray("roadCorrections").let { array ->
            require(array.length() <= 10_000) { "Too many road corrections" }
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val value = RoadLimitCorrection(item.getString("id"), RoadLimitKind.valueOf(item.getString("kind")),
                    if (item.isNull("mph")) null else item.getInt("mph"),
                    if (item.isNull("sourceValue")) null else item.getString("sourceValue"), item.getLong("updated"))
                require(value.id.length <= 100 && (value.sourceValue?.length ?: 0) <= 100) { "Invalid road correction" }
                value
            }.also { require(it.map(RoadLimitCorrection::id).distinct().size == it.size) { "Duplicate road corrections" } }
        }
        val aliases=if(root.getInt("version")<6) emptyList() else root.getJSONArray("cameraAliases").let { a ->
            require(a.length()<=20_000) { "Too many camera links" }
            (0 until a.length()).map { index ->
                val pair=a.getJSONArray(index);require(pair.length()==2)
                val first=pair.getString(0);val second=pair.getString(1)
                require(first.startsWith("node/") && second.startsWith("lufop:") && first.length<=100 && second.length<=100) { "Invalid camera link" }
                first to second
            }.distinct()
        }
        return OwnerBackup(cameras, settings, corrections, roadLimits, hidden, roadCorrections,aliases)
    }

    fun applySettings(settings: Map<String, Any>, prefs: SharedPreferences) {
        val editor = prefs.edit()
        settings.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key,value)
        } }
        check(editor.commit()) { "Could not save settings" }
    }
}
