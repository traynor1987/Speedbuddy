package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OwnerBackup(val cameras: List<Camera>, val settings: Map<String, Any>,
    val roadOverrides: List<RoadDb.Override> = emptyList(), val boundaries: List<BoundaryCorrection> = emptyList(),
    val legacyArchives: List<String> = emptyList(), val archivedOnly: List<String> = emptyList(), val sourceText: String = "")

/** Portable JSON only; unknown future formats fail before storage mutation. */
object OwnerBackupCodec {
    const val MAX_CHARS = 10_000_000
    private val booleans = mapOf("overspeed" to false, "speedCamera" to true, "redCamera" to true,
        "cameraSound" to true, "vibrate" to true)
    private val legacySections = mapOf("cameraCorrections" to "camera corrections", "roadLimits" to "legacy speed-limit overrides",
        "suppressedCameraIds" to "suppressed cameras", "roadCorrections" to "legacy road corrections",
        "cameraAliases" to "camera links", "junctions" to "junctions")
    fun read(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder(); val buffer = CharArray(8192)
        while (true) {
            val size = reader.read(buffer); if (size < 0) break
            require(result.length + size <= MAX_CHARS) { "Backup is too large" }
            result.append(buffer, 0, size)
        }
        result.toString()
    }
    fun export(cameras: List<Camera>, prefs: SharedPreferences,
        roadOverrides: List<RoadDb.Override> = emptyList(), boundaries: List<BoundaryCorrection> = emptyList(),
        legacyArchives: List<String> = emptyList()): String {
        val records = JSONArray()
        cameras.filter { it.source == CameraSource.USER }.forEach { c ->
            records.put(JSONObject().put("id", c.id).put("lat", c.point.lat).put("lon", c.point.lon)
                .put("type", c.type.name).put("direction", c.direction ?: JSONObject.NULL)
                .put("mph", c.enforcedMph ?: JSONObject.NULL).put("note", c.note ?: JSONObject.NULL).put("updated", c.updatedAtMs))
        }
        val settings = JSONObject()
        booleans.forEach { (key, default) -> settings.put(key, prefs.getBoolean(key, default)) }
        settings.put("tolerance", prefs.getInt("tolerance", 2))
        val text = JSONObject().put("format", "speed-buddy-owner-backup").put("version", 10)
            .put("cameras", records).put("settings", settings)
            .put("roadOverrides", JSONArray(roadOverrides.map(RoadJson::override)))
            .put("boundaries", JSONArray(boundaries.map(RoadJson::boundary)))
            .put("legacyArchives", JSONArray(legacyArchives.distinct())).toString(2)
        parse(text)
        return text
    }
    fun parse(text: String): OwnerBackup {
        require(text.length <= MAX_CHARS) { "Backup is too large" }
        val root = JSONObject(text); val version = root.getInt("version")
        require(root.getString("format") == "speed-buddy-owner-backup" && version in 1..10) { "Unsupported Speed Buddy backup" }
        if (version >= 9) requireKnownKeys(root, setOf("format", "version", "cameras", "settings", "roadOverrides", "boundaries", "legacyArchives"))
        val records = root.getJSONArray("cameras"); require(records.length() <= 10_000)
        val archived = linkedSetOf<String>()
        val cameras = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index); val id = item.getString("id")
            if (version >= 9) requireKnownKeys(item, setOf("id", "lat", "lon", "type", "direction", "mph", "note", "updated"))
            val lat = item.getDouble("lat"); val lon = item.getDouble("lon")
            val direction = if (item.isNull("direction")) null else item.getDouble("direction")
            val mph = if (item.isNull("mph")) null else item.getInt("mph")
            val note = if (item.isNull("note")) null else item.getString("note")
            require(id.isNotBlank() && id.length <= 100 && lat.isFinite() && lat in -90.0..90.0 &&
                lon.isFinite() && lon in -180.0..180.0 && (direction == null || direction.isFinite() && direction in 0.0..<360.0) &&
                (mph == null || mph in 5..130) && (note == null || note.length <= 100)) { "Invalid camera record" }
            if (item.optBoolean("bidirectional", false)) archived.add("bidirectional camera flags")
            if (item.has("junctionId") && !item.isNull("junctionId")) archived.add("camera junction membership")
            Camera(id, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")), CameraSource.USER,
                direction, mph, note, item.getLong("updated"))
        }
        require(cameras.map { it.id }.distinct().size == cameras.size) { "Duplicate camera IDs" }
        val source = root.getJSONObject("settings")
        if (version >= 9) requireKnownKeys(source, booleans.keys + "tolerance")
        val settings = buildMap<String, Any> {
            booleans.forEach { (key, _) -> if (source.has(key)) {
                require(source.get(key) is Boolean) { "Invalid setting: $key" }; put(key, source.getBoolean(key))
            } }
            if (source.has("tolerance")) {
                val value = source.getInt("tolerance"); require(value in listOf(0, 1, 2, 3, 5)) { "Invalid warning threshold" }
                put("tolerance", value)
            }
        }
        if (source.keys().asSequence().any { it !in booleans && it != "tolerance" }) archived.add("additional preview settings")
        legacySections.forEach { (field, description) ->
            val value = root.opt(field)
            if ((value is JSONArray && value.length() > 0) || (value is JSONObject && value.length() > 0)) archived.add(description)
        }
        val overrides = if (version < 9) emptyList() else root.getJSONArray("roadOverrides").let { array ->
            require(array.length() <= 10_000)
            (0 until array.length()).map { i -> val item = array.getJSONObject(i)
                requireKnownKeys(item, if(version==9) setOf("road", "bearing", "mph") else setOf("road","bearing","mph","source","point","at","accuracy"))
                RoadJson.decodeOverride(item).also {
                    require(it.road.isNotBlank() && it.road.length <= 100 && it.bearing.isFinite() &&
                        it.bearing in 0.0..<360.0 && (if(version==9) it.mph in 5..100 else OwnerLimit.valid(it.mph)) &&
                        (it.sourceMph==null || it.sourceMph in 5..100) && (it.point==null || validPoint(it.point)) &&
                        it.recordedAt>=0 && (it.accuracy==null || it.accuracy.isFinite() && it.accuracy in 0.0..20.0)) { "Invalid road override" }
                    if(!item.isNull("point")) require(item.getJSONArray("point").length()==2)
                }
            }.also { require(it.map { row -> row.road to row.bearing }.distinct().size == it.size) }
        }
        val boundaries = if (version < 9) emptyList() else root.getJSONArray("boundaries").let { array ->
            require(array.length() <= 10_000)
            (0 until array.length()).map { i -> val item = array.getJSONObject(i)
                requireKnownKeys(item, setOf("from", "to", "old", "new", "predicted", "observed", "bearing", "pa", "oa", "confidence", "distance", "at"))
                require(item.getJSONArray("predicted").length() == 2 && item.getJSONArray("observed").length() == 2)
                RoadJson.decodeBoundary(item).also(::validateBoundary)
            }
                .also { require(it.map { row -> Triple(row.fromId, row.toId, row.bearing) }.distinct().size == it.size) }
        }
        val archives = if (version < 9) listOf(text) else root.getJSONArray("legacyArchives").let { array ->
            require(array.length() <= 100)
            (0 until array.length()).map { i ->
                val legacy = array.getString(i)
                require(JSONObject(legacy).getInt("version") in 1..8) { "Invalid retained legacy backup" }
                archived.addAll(parse(legacy).archivedOnly); legacy
            }.distinct()
        }
        return OwnerBackup(cameras, settings, overrides, boundaries, archives, archived.toList(), text)
    }
    private fun requireKnownKeys(json: JSONObject, allowed: Set<String>) {
        require(json.keys().asSequence().all { it in allowed }) { "Backup contains unsupported fields; retain the original file" }
    }
    private fun validateBoundary(b: BoundaryCorrection) {
        require(b.fromId.isNotBlank() && b.fromId.length <= 100 && b.toId.isNotBlank() && b.toId.length <= 100 &&
            b.fromId != b.toId && b.oldMph in 5..100 && b.newMph in 5..100 && validPoint(b.predicted) && validPoint(b.observed) &&
            b.bearing.isFinite() && b.bearing in 0.0..<360.0 &&
            b.predictedAccuracy.isFinite() && b.predictedAccuracy >= 0 && b.observedAccuracy.isFinite() && b.observedAccuracy >= 0 &&
            b.confidence.isFinite() && b.confidence in 0.0..1.0 && b.matchDistance.isFinite() && b.matchDistance >= 0 &&
            b.recordedAt >= 0) { "Invalid learned boundary" }
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

/** Retain and re-read complete source JSON before applying any restore records. */
class OwnerBackupStore(context: Context) {
    private val directory = File(context.filesDir, "owner-backups")
    private fun save(folder: String, text: String) {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val parent = File(directory, folder); check(parent.isDirectory || parent.mkdirs()) { "Could not create backup archive" }
        val atomic = AtomicFile(File(parent, "$hash.json")); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        check(atomic.openRead().use(OwnerBackupCodec::read) == text) { "Backup archive verification failed" }
    }
    fun legacyArchives(): List<String> = File(directory, "legacy").listFiles()?.filter { it.extension == "json" }
        ?.sortedBy { it.name }?.map { AtomicFile(it).openRead().use(OwnerBackupCodec::read) } ?: emptyList()
    fun retainBeforeApply(backup: OwnerBackup) {
        require(backup.sourceText.isNotBlank()); save("restore-receipts", backup.sourceText)
        backup.legacyArchives.forEach { save("legacy", it) }
    }
    fun restore(backup: OwnerBackup, db: CameraDb, roads: RoadDb, prefs: SharedPreferences) {
        retainBeforeApply(backup)
        db.merge(backup.cameras); roads.mergeOwnerCorrections(backup.roadOverrides, backup.boundaries)
        OwnerBackupCodec.applySettings(backup.settings, prefs)
        // Interrupted multi-store restore can be retried from the retained complete JSON.
    }
}

/** Process-wide exclusion survives Activity recreation and blocking IO cancellation. */
object OwnerBackupWork {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    suspend fun <T> perform(block: suspend () -> T): T {
        check(mutableBusy.compareAndSet(false, true)) { "Another owner backup operation is running" }
        try { return block() } finally { mutableBusy.value = false }
    }
}
