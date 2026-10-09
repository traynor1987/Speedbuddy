package uk.co.traynor.speedbuddy

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Actual provider evidence, independently of owner overrides and displayed assumptions. */
data class RegionalPackInfo(val name: String,val id: String,val version: String,val osmTimestamp: String) {
    fun ageMs(now: Long): Long?=runCatching { Instant.parse(osmTimestamp).toEpochMilli().takeIf { it<=now }?.let { now-it } }.getOrNull()
}
data class RoadDataDiagnostics(
    val provider: String="None",val providerState: String="Not evaluated",val coverage: String="Not evaluated",
    val packs: List<RegionalPackInfo> = emptyList(),val sampleElapsedMs: Long?=null,
    val fallbackReason: String?=null,val error: String?=null,val contextComplete: Boolean?=null, val liveRequestStatus: String="Not requested",val liveSampleElapsedMs: Long?=null,
) {
    fun json(confidence: Double?=null): JSONObject=JSONObject().put("provider",provider).put("providerState",providerState)
        .put("liveRequestStatus",liveRequestStatus).put("liveSampleElapsedMs",liveSampleElapsedMs ?: JSONObject.NULL)
        .put("coverage",coverage).put("sampleElapsedMs",sampleElapsedMs ?: JSONObject.NULL)
        .put("fallbackReason",fallbackReason ?: JSONObject.NULL).put("error",error ?: JSONObject.NULL)
        .put("contextComplete",contextComplete ?: JSONObject.NULL).put("confidence",confidence ?: JSONObject.NULL)
        .put("packs",JSONArray(packs.map { JSONObject().put("id",it.id).put("name",it.name).put("version",it.version).put("osmTimestamp",it.osmTimestamp) }))
    fun rows(now: Long=System.currentTimeMillis(),elapsed: Long): List<Pair<String,String?>> = listOf(
        "Live request" to liveRequestStatus,"Live response age" to liveSampleElapsedMs?.let { "${(elapsed-it).coerceAtLeast(0)} ms" },
        "Active provider" to provider,"Provider state" to providerState,"Regional coverage" to coverage,
        "Regional packs" to packs.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.name} · ${it.version} · ${it.osmTimestamp}" },
        "Regional data age" to packs.takeIf { it.isNotEmpty() }?.joinToString("\n") { p -> "${p.name}: ${p.ageMs(now)?.let { "${it/86_400_000} days" } ?: "Unknown"}" },
        "Match sample age" to sampleElapsedMs?.takeIf { it<=elapsed }?.let { "${(elapsed-it)/1000} s" },
        "Context complete" to contextComplete?.toString(),"Fallback reason" to fallbackReason,"Regional error" to error)
}
