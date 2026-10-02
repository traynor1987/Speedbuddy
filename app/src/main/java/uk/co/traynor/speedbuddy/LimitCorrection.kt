package uk.co.traynor.speedbuddy

// Stored selections, distinct from missing owner feedback. Never feed these into numeric alerts.
const val OWNER_UNKNOWN = 0
const val OWNER_NATIONAL = -1
object OwnerLimit {
    val choices = listOf(20,30,40,50,60,70,OWNER_NATIONAL,OWNER_UNKNOWN)
    fun valid(value: Int) = value in 5..100 || value == OWNER_UNKNOWN || value == OWNER_NATIONAL
    fun nationalMph(road: Road): Int? = when {
        road.tags["highway"] == "motorway" -> 70
        else -> listOf("maxspeed", "maxspeed:type", "source:maxspeed").mapNotNull { key ->
            road.tags[key]?.lowercase()?.takeIf { it.startsWith("gb:nsl_") || it == "gb:motorway" }
                ?.let { SpeedLimits.mph(mapOf("maxspeed" to it)) }
        }.firstOrNull()
    }
}

/** The picker sends its road identity; the service validates it against a fresh live match. */
object QuickLimitCorrection {
    fun capture(fix: Fix,match: RoadMatch?,source: Int?,selected: Int,targetRoad: String,now: Long): RoadDb.Override? {
        if(match==null || match.road.id!=targetRoad || !OwnerLimit.valid(selected) ||
            now-fix.elapsedMs !in 0..5000 || !validPoint(fix.point) || !fix.accuracyM.isFinite() ||
            fix.accuracyM !in 0.0..20.0 || match.confidence<.7 ||
            (match.headingDifference ?: 90.0)>30 || fix.bearing==null || !fix.bearing.isFinite() ||
            fix.bearing !in 0.0..<360.0 || Geo.projection(fix.point,match.road.points).first>25) return null
        return RoadDb.Override(match.road.id,fix.bearing,selected,source,fix.point,System.currentTimeMillis(),fix.accuracyM)
    }
}
