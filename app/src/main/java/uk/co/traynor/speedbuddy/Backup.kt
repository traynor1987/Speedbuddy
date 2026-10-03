package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class OwnerBackup(val cameras: List<Camera>, val settings: Map<String, Any>,
    val corrections: List<CameraCorrection> = emptyList(), val roadLimits: Map<String, Int> = emptyMap(),
    val suppressedCameraIds: Set<String> = emptySet(),
    val roadCorrections: List<RoadLimitCorrection> = emptyList(),
    val aliases: List<Pair<String,String>> = emptyList(),
    val junctions: List<CameraJunction> = emptyList(),
    val roadOverrides: List<RoadDb.Override> = emptyList(), val boundaries: List<BoundaryCorrection> = emptyList(),
    val legacyArchives: List<String> = emptyList(), val archivedOnly: List<String> = emptyList(), val sourceText: String = "", val boundaryObservations: List<BoundaryObservation> = emptyList())

/** A portable, versioned owner export. No route history or public OSM database is included. */
object OwnerBackupCodec {
    const val MAX_CHARS = 10_000_000
    fun read(input: InputStream): String = input.bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder(); val buffer = CharArray(8192)
        while (true) {
            val size = reader.read(buffer); if (size < 0) break
            require(result.length + size <= MAX_CHARS) { "Backup is too large" }
            result.append(buffer, 0, size)
        }
        result.toString()
    }
    private val booleans = mapOf("overspeed" to false, "speedCamera" to true, "redCamera" to true,
        "cameraSound" to true, "vibrate" to true, "limitVoice" to true, "keepAwake" to true, "fixedCamera" to true, "mobileCamera" to true)
    fun export(cameras: List<Camera>, prefs: SharedPreferences,
        corrections: List<CameraCorrection> = emptyList(), roadLimits: Map<String, Int> = emptyMap(),
        suppressedCameraIds: Set<String> = emptySet(),
        roadCorrections: List<RoadLimitCorrection> = emptyList(), aliases: List<Pair<String,String>> = emptyList(),
        junctions: List<CameraJunction> = emptyList(),
        roadOverrides: List<RoadDb.Override> = emptyList(), boundaries: List<BoundaryCorrection> = emptyList(),
        legacyArchives: List<String> = emptyList(), boundaryObservations: List<BoundaryObservation> = emptyList()): String {
        val groups = (junctions + cameras.mapNotNull { it.junction }).distinctBy { it.id }
        groups.forEach(JunctionRules::validate)
        val records = JSONArray()
        cameras.filter { it.source == CameraSource.USER && it.type != CameraType.MOBILE }.forEach { camera ->
            records.put(JSONObject().put("id", camera.id).put("lat", camera.point.lat).put("lon", camera.point.lon)
                .put("type", camera.type.name).put("direction", camera.direction ?: JSONObject.NULL)
                .put("mph", camera.enforcedMph ?: JSONObject.NULL).put("note", camera.note ?: JSONObject.NULL)
                .put("updated", camera.updatedAtMs).put("bidirectional", camera.bidirectional)
                .put("junctionId", camera.junction?.id ?: JSONObject.NULL))
        }
        val settings = JSONObject()
        booleans.forEach { (key, default) -> settings.put(key, prefs.getBoolean(key, default)) }
        settings.put("mobileLifetime",prefs.getInt("mobileLifetime",120).takeIf { it in MobileReportCapture.lifetimes } ?: 120)
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
        val text = JSONObject().put("format", "speed-buddy-owner-backup").put("version", 12)
            .put("roadOverrides", JSONArray(roadOverrides.map(RoadJson::override)))
            .put("boundaries", JSONArray(boundaries.map(RoadJson::boundary)))
            .put("boundaryObservations",JSONArray(boundaryObservations.map(RoadJson::observation)))
            .put("legacyArchives", JSONArray(legacyArchives.distinct()))
            .put("cameras", records).put("settings", settings)
            .put("cameraCorrections", overrides).put("roadLimits", limits)
            .put("suppressedCameraIds", JSONArray(suppressedCameraIds.sorted()))
            .put("roadCorrections", roadRecords)
            .put("cameraAliases",JSONArray(aliases.map { JSONArray(listOf(it.first,it.second)) }))
            .put("junctions", JSONArray(groups.map { group -> JSONObject().put("id", group.id).put("name", group.name)
                .put("lat", group.point.lat).put("lon", group.point.lon).put("ways", group.ways) })).toString(2)
        parse(text)
        return text
    }

    fun parse(text: String): OwnerBackup {
        require(text.length <= MAX_CHARS) { "Backup is too large" }
        val root = JSONObject(text)
        val version = root.getInt("version")
        require(root.getString("format") == "speed-buddy-owner-backup" && version in 1..12) {
            "Unsupported Speed Buddy backup"
        }
        val mapFields = version <= 8 || version >= 11
        val mapKeys = setOf("cameraCorrections", "roadLimits", "suppressedCameraIds", "roadCorrections", "cameraAliases", "junctions")
        val baseKeys = setOf("format", "version", "cameras", "settings")
        val offlineKeys = setOf("roadOverrides", "boundaries", "legacyArchives")
        if (version >= 9) {
            requireKnownKeys(root, baseKeys + offlineKeys + (if (version >= 11) mapKeys else emptySet()) + (if(version>=12) setOf("boundaryObservations") else emptySet()))
            requireExactInteger(root, "version")
        }
        val archived = linkedSetOf<String>()
        if (version <= 8 && root.keys().asSequence().any { it !in baseKeys + mapKeys }) archived.add("additional legacy owner records")
        val junctions = if (!mapFields || version < 8) emptyList() else root.getJSONArray("junctions").let { array ->
            require(array.length() <= 500) { "Too many junctions" }
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                if (version >= 11) { requireKnownKeys(item, setOf("id", "name", "lat", "lon", "ways")); requireExactInteger(item, "ways") }
                CameraJunction(item.getString("id"), item.getString("name"),
                    GeoPoint(item.getDouble("lat"), item.getDouble("lon")), item.getInt("ways")).also(JunctionRules::validate)
            }.also { require(it.map { group -> group.id }.distinct().size == it.size) { "Duplicate junction IDs" } }
        }
        val groups = junctions.associateBy { it.id }
        val records = root.getJSONArray("cameras")
        require(records.length() <= 10_000) { "Too many camera records" }
        val cameras = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            if (version >= 9) {
                requireKnownKeys(item, setOf("id", "lat", "lon", "type", "direction", "mph", "note", "updated") +
                    if (version >= 11) setOf("bidirectional", "junctionId") else emptySet())
                requireExactInteger(item, "mph"); requireExactInteger(item, "updated")
                if (item.has("bidirectional")) require(item.get("bidirectional") is Boolean)
            }
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
            val group = if (!mapFields || version < 8 || item.isNull("junctionId")) null else
                groups[item.getString("junctionId")] ?: error("Camera refers to an unknown junction")
            Camera(id, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")).also { require(it != CameraType.MOBILE) { "Temporary reports are not permanent cameras" } }, CameraSource.USER,
                direction, mph, note, item.getLong("updated"), both, junction = group).also {
                    if (group != null) JunctionRules.validateMember(it, group)
                }
        }
        require(cameras.map { it.id }.distinct().size == cameras.size) { "Duplicate camera IDs" }
        val source = root.getJSONObject("settings")
        val settingsKeys = booleans.keys + setOf("mobileLifetime", "theme", "tolerance")
        if (version >= 9) requireKnownKeys(source, if (version >= 11) settingsKeys else
            setOf("overspeed", "speedCamera", "redCamera", "cameraSound", "vibrate", "tolerance"))
        if (version <= 8 && source.keys().asSequence().any { it !in settingsKeys }) archived.add("additional legacy settings")
        if (version >= 9) { requireExactInteger(source, "tolerance"); requireExactInteger(source, "mobileLifetime") }
        val settings = buildMap<String, Any> {
            booleans.forEach { (key, _) -> if (source.has(key)) {
                require(source.get(key) is Boolean) { "Invalid setting: $key" }
                put(key, source.getBoolean(key))
            } }
            if(source.has("mobileLifetime")) {
                val value=source.getInt("mobileLifetime");require(value in MobileReportCapture.lifetimes) { "Invalid mobile report lifetime" };put("mobileLifetime",value)
            }
            if(source.has("theme")) {
                val theme=source.getString("theme");require(theme in listOf("system","light","dark")) { "Invalid appearance" };put("theme",theme)
            }
            if (source.has("tolerance")) {
                val value = source.getInt("tolerance")
                require(value in listOf(0, 1, 2, 3, 5)) { "Invalid warning threshold" }
                put("tolerance", value)
            }
        }
        val corrections = if (!mapFields || version == 1) emptyList() else {
            val array = root.getJSONArray("cameraCorrections")
            require(array.length() <= 10_000) { "Too many camera corrections" }
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                if (version >= 11) {
                    requireKnownKeys(item, setOf("id", "source", "lat", "lon", "type", "direction", "note", "mph", "sourceLat", "sourceLon", "updated", "bidirectional"))
                    requireExactInteger(item, "mph"); requireExactInteger(item, "updated")
                    if (item.has("bidirectional")) require(item.get("bidirectional") is Boolean)
                }
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
                CameraCorrection(id, sourceType, GeoPoint(lat, lon), CameraType.valueOf(item.getString("type")).also { require(it != CameraType.MOBILE) },
                    direction, note, mph, sourcePoint, updated, both)
            }.also { require(it.map(CameraCorrection::id).distinct().size == it.size) { "Duplicate camera corrections" } }
        }
        val roadLimits = if (!mapFields || version == 1) emptyMap() else root.getJSONObject("roadLimits").let { limits ->
            require(limits.length() <= 10_000) { "Too many road corrections" }
            limits.keys().asSequence().associateWith { id ->
                if (version >= 11) requireExactInteger(limits, id)
                val mph = limits.getInt(id)
                require(id.startsWith("way/") && id.length <= 100 && mph in 5..130) { "Invalid road limit correction" }
                mph
            }
        }
        val hidden = if (!mapFields || version < 3) emptySet() else root.getJSONArray("suppressedCameraIds").let { a ->
            require(a.length() <= 10_000) { "Too many hidden cameras" }
            (0 until a.length()).map(a::getString).onEach {
                require(it.length <= 100 && (it.startsWith("lufop:") || it.startsWith("node/"))) { "Invalid hidden camera" }
            }.toSet().also { require(it.size == a.length()) { "Duplicate hidden camera" } }
        }
        val roadCorrections = if (!mapFields || version < 4) emptyList() else root.getJSONArray("roadCorrections").let { array ->
            require(array.length() <= 10_000) { "Too many road corrections" }
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                if (version >= 11) {
                    requireKnownKeys(item, setOf("id", "kind", "mph", "sourceValue", "updated"))
                    requireExactInteger(item, "mph"); requireExactInteger(item, "updated")
                }
                val value = RoadLimitCorrection(item.getString("id"), RoadLimitKind.valueOf(item.getString("kind")),
                    if (item.isNull("mph")) null else item.getInt("mph"),
                    if (item.isNull("sourceValue")) null else item.getString("sourceValue"), item.getLong("updated"))
                require(value.id.length <= 100 && (value.sourceValue?.length ?: 0) <= 100) { "Invalid road correction" }
                value
            }.also { require(it.map(RoadLimitCorrection::id).distinct().size == it.size) { "Duplicate road corrections" } }
        }
        val aliases=if(!mapFields || version<6) emptyList() else root.getJSONArray("cameraAliases").let { a ->
            require(a.length()<=20_000) { "Too many camera links" }
            (0 until a.length()).map { index ->
                val pair=a.getJSONArray(index);require(pair.length()==2)
                val first=pair.getString(0);val second=pair.getString(1)
                require(first.startsWith("node/") && second.startsWith("lufop:") && first.length<=100 && second.length<=100) { "Invalid camera link" }
                first to second
            }.distinct()
        }
        val roadOverrides = if (version < 9) emptyList() else root.getJSONArray("roadOverrides").let { array ->
            require(array.length() <= 10_000)
            (0 until array.length()).map { i -> val item = array.getJSONObject(i)
                requireExactInteger(item, "mph"); requireExactInteger(item, "source"); requireExactInteger(item, "at")
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
                requireExactInteger(item, "old"); requireExactInteger(item, "new"); requireExactInteger(item, "at")
                requireKnownKeys(item, setOf("from", "to", "old", "new", "predicted", "observed", "bearing", "pa", "oa", "confidence", "distance", "at") + (if(version>=12) setOf("via","still","shared") else emptySet()))
                require(item.getJSONArray("predicted").length() == 2 && item.getJSONArray("observed").length() == 2)
                RoadJson.decodeBoundary(item).also(::validateBoundary)
            }
                .also { require(it.map { row -> Triple(row.fromId, row.toId, row.bearing) }.distinct().size == it.size) }
        }
        val observations=if(version<12) emptyList() else root.getJSONArray("boundaryObservations").let { array ->
            require(array.length()<=1000)
            (0 until array.length()).map { i -> val item=array.getJSONObject(i)
                requireKnownKeys(item,setOf("from","to","old","new","predicted","still","bearing","pa","accuracy","at"))
                requireExactInteger(item,"old");requireExactInteger(item,"new");requireExactInteger(item,"at")
                RoadJson.decodeObservation(item).also { it.validate() }
            }
        }
        val archives = if (version <= 8) listOf(text) else root.getJSONArray("legacyArchives").let { array ->
            require(array.length() <= 100) { "Too many retained legacy backups" }
            (0 until array.length()).map { index ->
                val legacy = array.getString(index)
                require(JSONObject(legacy).getInt("version") in 1..8) { "Invalid retained legacy backup" }
                legacy
            }.distinct()
        }
        val direct = OwnerBackup(cameras, settings, corrections, roadLimits, hidden, roadCorrections, aliases, junctions,
            roadOverrides, boundaries, archives, archived.toList(), text, observations)
        if (version <= 8 || archives.isEmpty()) return direct
        val legacy = archives.map(::parse)
        // Full-format archives are receipts, not active records: owner deletions must stay deleted.
        if (version >= 11) return direct.copy(archivedOnly = (direct.archivedOnly + legacy.flatMap { it.archivedOnly }).distinct())
        return recoverArchivedMapData(direct, legacy)

    }

    private fun recoverArchivedMapData(direct: OwnerBackup, legacy: List<OwnerBackup>): OwnerBackup {
        val latestCameras = legacy.flatMap { it.cameras }.sortedByDescending { it.updatedAtMs }.distinctBy { it.id }
        val groups = (latestCameras.mapNotNull { it.junction } + legacy.flatMap { it.junctions }).distinctBy { it.id }
        val groupById = groups.associateBy { it.id }
        val oldCameras = latestCameras.associateBy { it.id }
        val archived = (direct.archivedOnly + legacy.flatMap { it.archivedOnly }).toMutableSet()
        val cameras = (direct.cameras + latestCameras).distinctBy { it.id }.map { camera ->
            val old = oldCameras[camera.id]
            val recovered = if (old == null) camera else camera.copy(
                bidirectional = old.bidirectional && camera.direction != null,
                junction = old.junction?.let { groupById[it.id] })
            if (recovered.junction != null && runCatching { JunctionRules.validateMember(recovered, recovered.junction) }.isFailure) {
                archived.add("incompatible archived junction membership")
                recovered.copy(junction = null)
            } else recovered
        }
        val directRoadIds = direct.roadLimits.keys + direct.roadCorrections.map { it.id }
        return direct.copy(cameras = cameras,
            settings = legacy.asReversed().fold(emptyMap<String, Any>()) { all, item -> all + item.settings } + direct.settings,
            corrections = (direct.corrections + legacy.flatMap { it.corrections }.sortedByDescending { it.updatedAtMs }).distinctBy { it.id },
            roadLimits = legacy.asReversed().fold(emptyMap<String, Int>()) { all, item -> all + item.roadLimits } + direct.roadLimits,
            suppressedCameraIds = direct.suppressedCameraIds + legacy.flatMap { it.suppressedCameraIds },
            roadCorrections = direct.roadCorrections + legacy.flatMap { it.roadCorrections }.filter { it.id !in directRoadIds }
                .sortedByDescending { it.updatedAtMs }.distinctBy { it.id },
            aliases = (direct.aliases + legacy.flatMap { it.aliases }).distinct(), junctions = groups,
            archivedOnly = archived.toList())
    }
    private fun requireExactInteger(json: JSONObject, key: String) {
        if (!json.has(key) || json.isNull(key)) return
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "Invalid integer: $key" }
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
            b.recordedAt >= 0 && b.viaIds.size<=4 && b.viaIds.all { it.isNotBlank() && it.length<=100 && it!=b.fromId && it!=b.toId } && (b.stillPoint==null || validPoint(b.stillPoint))) { "Invalid learned boundary" }
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
        db.restoreOwnerData(backup, prefs)
        roads.mergeOwnerCorrections(backup.roadOverrides, backup.boundaries,backup.boundaryObservations)
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
