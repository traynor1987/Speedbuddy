package uk.co.traynor.speedbuddy

enum class RoadLimitKind { NUMERIC, NATIONAL_SINGLE, NATIONAL_DUAL, UNKNOWN }

/** Direct Drive edits need a road identity and a fresh, precise position on its geometry. */
object DriveRoadEditTarget {
    fun select(fix: Fix?, match: RoadMatch?): Road? {
        if (fix == null || fix.accuracyM > 25 || match == null || match.confidence < .55 ||
            match.distanceM > 15 || !match.road.id.startsWith("way/")) return null
        return match.road.takeIf { Geo.projection(fix.point, it.points).first <= 15 }
    }
}

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
            "source:maxspeed:local")
        val value = when (kind) {
            RoadLimitKind.NUMERIC -> "$mph mph"
            RoadLimitKind.NATIONAL_SINGLE -> "GB:nsl_single"
            RoadLimitKind.NATIONAL_DUAL -> "GB:nsl_dual"
            RoadLimitKind.UNKNOWN -> null
        }
        return road.copy(tags = if (value == null) tags else tags + ("maxspeed" to value))
    }
}

internal data class SelectedOwnerLimit(val mph: Int, val national: Boolean = false)

/** Both existing edit surfaces supply owner evidence to the same driving decision engine. */
internal object OwnerRoadLimits {
    fun select(road: Road?, bearing: Double?, directed: List<RoadDb.Override>,
        mapCorrections: Map<String,RoadLimitCorrection>): SelectedOwnerLimit? {
        if(road==null) return null
        RoadDb.selectOverride(directed,road.id,bearing)?.let { return SelectedOwnerLimit(it,it==OWNER_NATIONAL) }
        return mapCorrections[road.id]?.let {
            SelectedOwnerLimit(if(it.kind==RoadLimitKind.UNKNOWN) OWNER_UNKNOWN else it.mph!!,
                it.kind in listOf(RoadLimitKind.NATIONAL_SINGLE,RoadLimitKind.NATIONAL_DUAL))
        }
    }
}
