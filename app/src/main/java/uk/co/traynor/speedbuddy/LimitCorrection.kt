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
    fun capture(fix: Fix,match: RoadMatch?,source: Int?,selected: Int,targetRoad: String,now: Long,directionSpecific: Boolean = false): RoadDb.Override? {
        if(match==null || !RoadIdentity.same(match.road.id,targetRoad) || !OwnerLimit.valid(selected) ||
            !correctionReady(fix, match, now)) return null
        return RoadDb.Override(match.road.id,fix.bearing ?: return null,selected,source,fix.point,System.currentTimeMillis(),fix.accuracyM,
            sharedAcrossDirections=!directionSpecific && CorrectionDirectionPolicy.ordinaryTwoWay(match.road))
    }
}

/** The UI and service share this gate, so the owner is never asked to retry blind. */
internal fun correctionReady(fix: Fix?, match: RoadMatch?, now: Long): Boolean {
    val bearing = fix?.bearing
    return fix != null && match != null && now-fix.elapsedMs in 0..5000 && validPoint(fix.point) &&
        fix.accuracyM.isFinite() && fix.accuracyM in 0.0..20.0 && match.confidence >= .7 &&
        (match.headingDifference ?: 90.0) <= 30 && bearing?.isFinite() == true &&
        bearing in 0.0..<360.0 && Geo.projection(fix.point, match.road.points).first <= 25
}

/** Explain the real prerequisite rather than asking the owner to guess where to tap. */
internal fun unmatchedCorrectionMessage() =
    "Waiting for a reliable road match. The + control becomes available when Speed Buddy has identified this road; then, when safely stopped, tap the large round limit sign."

/** One owner assertion, separate from road overrides and from downloaded data. */
data class BoundaryObservation(val from: Road,val to: Road,val oldMph: Int,val newMph: Int,
    val predicted: GeoPoint,val stillPoint: GeoPoint,val bearing: Double,
    val predictedAccuracy: Double,val accuracy: Double,val recordedAt: Long)
data class LimitSelectionPlan(val override: RoadDb.Override? = null,
    val observation: BoundaryObservation? = null,val boundary: BoundaryCorrection? = null,
    val consumed: BoundaryObservation? = null,val kind: String,val message: String)

internal fun connectedRoads(a: Road,b: Road)=a.id==b.id ||
    listOf(a.points.first(),a.points.last()).any { p -> listOf(b.points.first(),b.points.last()).any { Geo.distance(p,it)<=20 } }

internal fun BoundaryObservation.applies(fix: Fix,match: RoadMatch?,wallNow: Long): Boolean {
    if(match==null || wallNow-recordedAt !in 0..120_000 || Geo.distance(stillPoint,fix.point)>600 ||
        fix.accuracyM>20 || fix.bearing==null || Geo.difference(fix.bearing,bearing)>35 ||
        match.confidence<.7 || (match.headingDifference ?: 90.0)>30 ||
        Geo.projection(fix.point,match.road.points).first>25) return false
    return match.road.id in listOf(from.id,to.id) || connectedRoads(to,match.road) &&
        to.name!=null && match.road.name==to.name && match.road.tags["highway"]==to.tags["highway"]
}

internal fun BoundaryObservation.validate() {
    require(listOf(from,to).all { r -> r.id.isNotBlank() && r.id.length<=100 && r.points.size in 2..20_000 && r.points.all(::validPoint) })
    require(from.id!=to.id && connectedRoads(from,to) && oldMph in 5..100 && newMph in 5..100 && oldMph!=newMph &&
        validPoint(predicted) && validPoint(stillPoint) && bearing.isFinite() && bearing in 0.0..<360.0 &&
        accuracy.isFinite() && accuracy in 0.0..20.0 && predictedAccuracy.isFinite() && predictedAccuracy in 0.0..25.0 && recordedAt>=0)
}
