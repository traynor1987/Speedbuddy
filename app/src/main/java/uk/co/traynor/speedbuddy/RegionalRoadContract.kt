package uk.co.traynor.speedbuddy

import org.json.JSONObject
import java.net.URI

/** Strict adapter for the released Speed Buddy regional catalogue and live-road contract. */
internal data class RegionalPackDescriptor(
    val id: String, val displayName: String, val version: String, val schemaVersion: Int,
    val osmTimestamp: String, val downloadBytes: Long, val uncompressedBytes: Long,
    val sha256: String, val uncompressedSha256: String, val downloadUrl: String, val manifestUrl: String,
)

internal object RegionalRoadCatalogue {
    private const val host = "api.jtwebsolutions.co.uk"
    private val hex = Regex("[0-9a-f]{64}")
    fun parse(body: String): List<RegionalPackDescriptor> {
        val root = JSONObject(body)
        require(root.getInt("catalogueVersion") == 1) { "Unsupported regional catalogue" }
        val regions = root.getJSONArray("regions")
        return (0 until regions.length()).map { index -> regions.getJSONObject(index).let(::decode) }
            .also { require(it.map(RegionalPackDescriptor::id).distinct().size == it.size) { "Duplicate regional pack" } }
    }
    private fun decode(node: JSONObject): RegionalPackDescriptor {
        val id = node.getString("id")
        require(id.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid region id" }
        val version = node.getString("version")
        val download = node.getString("downloadUrl")
        val manifest = node.getString("manifestUrl")
        require(firstParty(download, "/speedbuddy/v1/packs/$id/$version/roads.sqlite.gz")) { "Untrusted pack URL" }
        require(firstParty(manifest, "/speedbuddy/v1/packs/$id/$version/manifest.json")) { "Untrusted manifest URL" }
        val compressed = node.getString("sha256").lowercase()
        val raw = node.getString("uncompressedSha256").lowercase()
        require(hex.matches(compressed) && hex.matches(raw)) { "Invalid pack digest" }
        val bytes = node.getLong("downloadBytes"); val rawBytes = node.getLong("uncompressedBytes")
        require(bytes in 1..128L*1024*1024 && rawBytes in 1..512L*1024*1024) { "Unsupported pack size" }
        require(node.getInt("schemaVersion") == 1) { "Unsupported road-pack schema" }
        return RegionalPackDescriptor(id,node.getString("displayName"),version,1,node.getString("osmTimestamp"),bytes,rawBytes,compressed,raw,download,manifest)
    }
    private fun firstParty(value: String, path: String): Boolean = runCatching {
        URI(value).let { it.scheme == "https" && it.host == host && it.rawQuery == null && it.rawFragment == null && it.path == path }
    }.getOrDefault(false)
}

internal enum class RoadProviderState(val permitsOverpass: Boolean) {
    ROAD_MATCHED_LIMIT_KNOWN(false), ROAD_MATCHED_LIMIT_UNKNOWN(false), ROAD_MATCH_UNCERTAIN(false),
    COVERAGE_UNAVAILABLE(true), SERVICE_UNAVAILABLE(true), UNKNOWN(false),
}
internal data class LiveRoadState(val state: RoadProviderState, val matched: Boolean, val limitMph: Int?, val fallbackAllowed: Boolean)
internal object LiveRoadStateParser {
    fun parse(body: String): LiveRoadState {
        val json = JSONObject(body)
        val state = runCatching { RoadProviderState.valueOf(json.getString("providerState")) }.getOrDefault(RoadProviderState.UNKNOWN)
        val limit = json.optJSONObject("limit")?.takeIf { state == RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN }?.getDouble("mph")
            ?.takeIf { it > 0 && it <= 130 }?.let { kotlin.math.round(it).toInt() }
        require(state != RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN || limit != null) { "Known road state needs numeric limit" }
        require(state != RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN || limit == null) { "Unknown road state must not contain numeric limit" }
        return LiveRoadState(state,json.optBoolean("matched"),limit,json.optBoolean("fallbackAllowed"))
    }
}
