package uk.co.traynor.speedbuddy

enum class RoadLimitKind { NUMERIC, NATIONAL_SINGLE, NATIONAL_DUAL, UNKNOWN }

data class RoadLimitCorrection(val id: String, val kind: RoadLimitKind, val mph: Int?,
    val sourceValue: String?, val updatedAtMs: Long) {
    init {
        require(id.startsWith("way/") && when (kind) {
            RoadLimitKind.NUMERIC -> mph != null && mph in 5..130
            RoadLimitKind.NATIONAL_SINGLE -> mph == 60
            RoadLimitKind.NATIONAL_DUAL -> mph == 70
            RoadLimitKind.UNKNOWN -> mph == null
        })
    }
    fun apply(road: Road): Road {
        if (road.id != id) return road
        val tags = road.tags - setOf("maxspeed", "maxspeed:type", "source:maxspeed",
            "maxspeed:conditional", "maxspeed:variable", "maxspeed:lanes",
            "maxspeed:forward", "maxspeed:backward")
        val value = when (kind) {
            RoadLimitKind.NUMERIC -> "$mph mph"
            RoadLimitKind.NATIONAL_SINGLE -> "GB:nsl_single"
            RoadLimitKind.NATIONAL_DUAL -> "GB:nsl_dual"
            RoadLimitKind.UNKNOWN -> null
        }
        return road.copy(tags = if (value == null) tags else tags + ("maxspeed" to value))
    }
}
