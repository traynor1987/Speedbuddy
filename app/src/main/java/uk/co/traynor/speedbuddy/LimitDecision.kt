package uk.co.traynor.speedbuddy

/** Original OSM tags remain untouched; this record is owner evidence for one directed transition. */
data class BoundaryCorrection(
    val fromId: String,val toId: String,val oldMph: Int,val newMph: Int,
    val predicted: GeoPoint,val observed: GeoPoint,val bearing: Double,
    val predictedAccuracy: Double,val observedAccuracy: Double,val confidence: Double,
    val matchDistance: Double,val recordedAt: Long,
)
data class LimitDecision(val mph: Int?,val upcoming: UpcomingLimit? = null,val ownerApplied: Boolean = false,
    val boundaryApplied: Boolean = false,val reason: String)
private data class Transition(val from: RoadMatch,val to: RoadMatch,val old: Int,val new: Int,
    val fix: Fix,val at: Long)

class LimitDecisionEngine {
    private val stabilizer = RoadLimitStabilizer()
    private val crossed = mutableSetOf<String>()
    private var lastMatch: RoadMatch? = null
    private var lastMph: Int? = null
    private var transition: Transition? = null
    private var pending: Transition? = null
    private var reportingAt = 0L
    private var currentRoadId: String? = null
    private var currentMatch: RoadMatch? = null
    private var reportable = false
    var pendingFeedback: Boolean = false; private set
    fun decide(fix: Fix,match: RoadMatch?,source: Int?,owner: Int?,boundaries: List<BoundaryCorrection>,now: Long): LimitDecision {
        currentRoadId = match?.road?.id
        val accepted = match?.takeIf { it.confidence >= .35 && fix.accuracyM <= 35 }
        currentMatch=accepted;reportable=fix.accuracyM<=25 && (accepted?.confidence ?: 0.0)>=.6
        val raw = owner ?: source
        if (owner != null && accepted != null) {
            cancelFeedback(); stabilizer.reset(); stabilizer.resolve(fix,accepted,owner,now)
            lastMatch = accepted; lastMph = owner; transition = null
            return LimitDecision(owner,ownerApplied=true,reason="Owner road correction")
        }
        val waiting = pending
        if (waiting != null && (now-reportingAt > 600_000 || Geo.distance(waiting.fix.point,fix.point)>2000 ||
                fix.bearing?.let { Geo.difference(it,waiting.fix.bearing!!) > 45 } != false ||
                accepted != null && accepted.road.id !in listOf(waiting.from.road.id,waiting.to.road.id))) cancelFeedback()
        pending?.let {
            if (accepted==null || fix.accuracyM>25 || accepted.road.id !in listOf(it.from.road.id,it.to.road.id))
                return LimitDecision(null,reason="Unavailable: insufficient GPS/road evidence while recording boundary")
            return LimitDecision(it.old,UpcomingLimit(it.new,0.0,false),reason="Owner reported early; waiting for Changed now")
        }
        if (accepted != null && raw != null) {
            fun key(b: BoundaryCorrection)="${b.fromId}|${b.toId}|${b.bearing}|${b.recordedAt}"
            boundaries.filter { it.fromId==accepted.road.id || it.toId==accepted.road.id && fix.bearing?.let { heading -> Geo.difference(heading,it.bearing)>100 } == true }
                .forEach { crossed.remove(key(it)) }
            val boundary = boundaries.firstOrNull { b -> b.toId == accepted.road.id && b.newMph == raw &&
                (lastMatch == null || lastMatch?.road?.id in listOf(b.fromId,b.toId)) &&
                fix.bearing?.let { Geo.difference(it,b.bearing)<40 } == true &&
                Geo.distance(fix.point,b.observed)<2000 && fix.accuracyM <= 25 &&
                Geo.projection(fix.point,accepted.road.points).first <= 35 }
            val crossedNow = boundary?.let { b ->
                val (_, geometryHeading, fraction)=Geo.projection(fix.point,accepted.road.points)
                val boundaryFraction=Geo.projection(b.observed,accepted.road.points).third
                val forward=geometryHeading?.let { h -> Geo.difference(fix.bearing!!,h)<90 } ?: true
                val pastOnGeometry=if(forward) fraction>boundaryFraction else fraction<boundaryFraction
                key(b) in crossed || passedBoundary(fix,b.observed,b.bearing) ||
                    pastOnGeometry && Geo.distance(fix.point,b.observed)>kotlin.math.max(8.0,fix.accuracyM)
            } == true
            if (boundary != null && !crossedNow) {
                lastMph = boundary.oldMph
                return LimitDecision(boundary.oldMph,UpcomingLimit(boundary.newMph,Geo.distance(fix.point,boundary.observed),false),
                    boundaryApplied=true,reason="Saved owner boundary not yet crossed")
            }
            if (boundary != null && crossedNow) {
                crossed.add(key(boundary))
                stabilizer.reset(); transition = null; lastMatch = accepted; lastMph = raw
                stabilizer.resolve(fix,accepted,raw,now)
                return LimitDecision(raw,boundaryApplied=true,reason="Crossed saved owner boundary")
            }
        }
        val displayed = stabilizer.resolve(fix,accepted,raw,now)
        val previous = lastMph
        if (displayed != null && accepted != null && displayed == raw) {
            if (previous != null && previous != displayed && lastMatch != null &&
                lastMatch!!.road.id != accepted.road.id && fix.bearing != null && fix.accuracyM <= 25)
                transition = Transition(lastMatch!!,accepted,previous,displayed,fix,now)
            lastMatch = accepted; lastMph = displayed
        } else if (displayed == null && accepted != null && raw == null) { lastMatch = null; lastMph = null; transition = null }
        val upcoming = raw?.takeIf { displayed != null && it != displayed }?.let { UpcomingLimit(it,0.0,false) }
        val reason = when {
            fix.accuracyM > 35 -> "Unavailable: GPS accuracy exceeds 35 m"
            accepted == null && displayed == null -> "Unavailable: no confident local road match"
            raw == null && accepted != null -> "Unavailable: source has missing or unsupported speed tags"
            upcoming != null -> "Confirming road transition"
            displayed == null -> "Unavailable: confirming a new road; prior geometry no longer fits"
            accepted == null -> "Brief match ambiguity on previous geometry"
            else -> "Confirmed local road limit"
        }
        return LimitDecision(displayed,upcoming,reason=reason)
    }
    fun canReport(now: Long) = reportable && pending == null && transition?.let { now-it.at in 0..30_000 && currentRoadId == it.to.road.id } == true
    fun tooEarly(fix: Fix,now: Long): Boolean {
        val t = transition ?: return false
        if (!canReport(now) || fix.accuracyM > 25 || fix.bearing == null ||
            Geo.difference(fix.bearing,t.fix.bearing!!) > 40 || Geo.distance(fix.point,t.fix.point)>600) return false
        pending = t; reportingAt = now; pendingFeedback = true
        return true
    }
    fun changedNow(fix: Fix,now: Long): BoundaryCorrection? {
        val t = pending ?: return null
        if (now-reportingAt !in 0..600_000 || fix.accuracyM > 25 || fix.bearing == null ||
            Geo.difference(fix.bearing,t.fix.bearing!!) > 40 || Geo.distance(t.fix.point,fix.point)>2000 ||
            currentRoadId != t.to.road.id || (currentMatch?.confidence ?: 0.0)<.6 || Geo.projection(fix.point,t.to.road.points).first > 35 ||
            !passedBoundary(fix,t.fix.point,t.fix.bearing!!)) return null
        val result = BoundaryCorrection(t.from.road.id,t.to.road.id,t.old,t.new,t.fix.point,fix.point,
            t.fix.bearing!!,t.fix.accuracyM,fix.accuracyM,t.to.confidence,t.to.distanceM,System.currentTimeMillis())
        // Activation is explicit only after the caller has persisted successfully.
        return result
    }
    fun feedbackSaved() { cancelFeedback(); transition = null; stabilizer.reset(); lastMatch = null; lastMph = null }
    fun cancelFeedback() { pending=null; pendingFeedback=false; transition=null; stabilizer.reset() }
    fun reset() { crossed.clear();cancelFeedback(); lastMatch=null; lastMph=null; currentRoadId=null }
}
