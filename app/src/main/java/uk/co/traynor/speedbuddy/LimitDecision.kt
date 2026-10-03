package uk.co.traynor.speedbuddy

/** Original OSM tags remain untouched; this record is owner evidence for one directed transition. */
data class BoundaryCorrection(
    val fromId: String,val toId: String,val oldMph: Int,val newMph: Int,
    val predicted: GeoPoint,val observed: GeoPoint,val bearing: Double,
    val predictedAccuracy: Double,val observedAccuracy: Double,val confidence: Double,
    val matchDistance: Double,val recordedAt: Long,
    val viaIds: List<String> = emptyList(),val stillPoint: GeoPoint? = null,
    /** True only when the observed transition is an ordinary shared two-way boundary. */
    val sharedAcrossDirections: Boolean = false,
)
data class LimitDecision(val mph: Int?,val upcoming: UpcomingLimit? = null,val ownerApplied: Boolean = false,
    val boundaryApplied: Boolean = false,val reason: String,val assumed: Boolean = false,
    val inheritedFrom: String? = null,val national: Boolean = false)
private data class Transition(val from: RoadMatch,val to: RoadMatch,val old: Int,val new: Int,
    val fix: Fix,val at: Long)
private data class ConfirmedLimit(val match: RoadMatch,val mph: Int,val fix: Fix,val at: Long)

class LimitDecisionEngine {
    private val stabilizer = RoadLimitStabilizer()
    private val crossed = mutableSetOf<String>()
    private fun key(b: BoundaryCorrection)="${b.fromId}|${b.toId}|${b.bearing}|${b.recordedAt}"
    internal fun confirmSavedBoundary(b: BoundaryCorrection) { crossed.add(key(b)) }
    private var lastMatch: RoadMatch? = null
    private var lastMph: Int? = null
    private var transition: Transition? = null
    private var candidateTransition: Transition? = null
    private var pending: Transition? = null
    private var reportingAt = 0L
    private var currentRoadId: String? = null
    private var currentMatch: RoadMatch? = null
    private var reportable = false
    private var confirmed: ConfirmedLimit? = null
    private var assumedMatch: RoadMatch? = null
    private var assumptionDistance = 0.0
    private var assumptionPoint: GeoPoint? = null
    private var assumptionDropReason = "No recent confirmed limit"
    var pendingFeedback: Boolean = false; private set
    fun decide(fix: Fix,match: RoadMatch?,source: Int?,owner: Int?,boundaries: List<BoundaryCorrection>,now: Long,
        observations: List<BoundaryObservation> = emptyList(),wallNow: Long = System.currentTimeMillis()): LimitDecision {
        currentRoadId = match?.road?.id
        val accepted = match?.takeIf { it.confidence >= .35 && fix.accuracyM <= 35 }
        currentMatch=accepted;reportable=fix.accuracyM<=25 && (accepted?.confidence ?: 0.0)>=.6
        val raw = owner ?: source
        observations.filter { it.applies(fix,accepted,wallNow) }.maxByOrNull { it.recordedAt }?.let { o ->
            // A later source gap must inherit the owner's current limit, not the
            // premature source value that was confirmed before this assertion.
            rememberConfirmed(fix,accepted!!,o.oldMph,now)
            return LimitDecision(o.oldMph,source?.takeIf { it!=o.oldMph }?.let { UpcomingLimit(it,0.0,false,roadId=accepted?.road?.id) }
                ?: UpcomingLimit(o.newMph,0.0,false,roadId=o.to.id),ownerApplied=true,boundaryApplied=true,
                reason="Owner confirmed still ${o.oldMph} here; waiting for forward boundary observation")
        }
        if (owner != null && accepted != null) {
            val candidate=currentTransition()?.takeIf { it.to.road.id==accepted.road.id && now-it.at in 0..120_000 }
                ?: lastMatch?.takeIf { prior -> lastMph!=null && source!=null && lastMph!=source &&
                    prior.road.id!=accepted.road.id && connectedRoads(prior.road,accepted.road) && fix.bearing!=null }
                    ?.let { prior -> Transition(prior,accepted,lastMph!!,source!!,fix,now) }
            clearAssumption(); cancelFeedback(); stabilizer.reset()
            val ownerMph = if(owner == OWNER_NATIONAL) OwnerLimit.nationalMph(accepted.road) else owner.takeIf { it > 0 }
            if(ownerMph != null) stabilizer.resolve(fix,accepted,ownerMph,now)
            lastMatch = accepted; lastMph = ownerMph; transition = candidate;candidateTransition=null
            rememberConfirmed(fix,accepted,ownerMph,now)
            return LimitDecision(ownerMph,ownerApplied=true,reason="Owner road correction",
                national=owner == OWNER_NATIONAL)
        }
        val waiting = pending
        if (waiting != null && (now-reportingAt > 600_000 || Geo.distance(waiting.fix.point,fix.point)>2000 ||
                fix.bearing?.let { Geo.difference(it,waiting.fix.bearing!!) > 45 } != false ||
                accepted != null && accepted.road.id !in listOf(waiting.from.road.id,waiting.to.road.id))) cancelFeedback()
        pending?.let {
            if (accepted==null || fix.accuracyM>25 || accepted.road.id !in listOf(it.from.road.id,it.to.road.id))
                return LimitDecision(null,reason="Unavailable: insufficient GPS/road evidence while recording boundary")
            return LimitDecision(it.old,UpcomingLimit(it.new,0.0,false,roadId=it.to.road.id),reason="Owner reported early; waiting for Changed now")
        }
        if (accepted != null) {
            boundaries.filter { it.fromId==accepted.road.id || it.toId==accepted.road.id && fix.bearing?.let { heading -> Geo.difference(heading,it.bearing)>100 } == true }
                .forEach { crossed.remove(key(it)) }
            val boundary = boundaries.asSequence().flatMap { boundary ->
                sequenceOf(boundary, boundary.mirroredFor(fix.bearing))
            }.filterNotNull().sortedByDescending { it.recordedAt }.firstOrNull { b ->
                accepted.road.id in b.viaIds+listOf(b.fromId,b.toId) && accepted.confidence>=.7 &&
                (lastMatch == null || lastMatch?.road?.id in b.viaIds+listOf(b.fromId,b.toId)) &&
                fix.bearing?.let { Geo.difference(it,b.bearing)<40 } == true &&
                Geo.distance(fix.point,b.observed)<2000 && fix.accuracyM <= 25 &&
                Geo.projection(fix.point,accepted.road.points).first <= 35 }
            val crossedNow = boundary?.let { b ->
                val (_, geometryHeading, fraction)=Geo.projection(fix.point,accepted.road.points)
                val boundaryFraction=Geo.projection(b.observed,accepted.road.points).third
                val forward=geometryHeading?.let { h -> Geo.difference(fix.bearing!!,h)<90 } ?: true
                val pastOnGeometry=if(forward) fraction>boundaryFraction else fraction<boundaryFraction
                val forwardDistance=Geo.distance(b.observed,fix.point)*kotlin.math.cos(Math.toRadians(
                    Geo.difference(b.bearing,Geo.bearing(b.observed,fix.point))))
                val uncertainty=kotlin.math.max(8.0,fix.accuracyM+b.observedAccuracy)
                accepted.road.id==b.toId && (key(b) in crossed ||
                    passedBoundary(fix,b.observed,b.bearing) && forwardDistance>uncertainty ||
                    pastOnGeometry && Geo.distance(fix.point,b.observed)>kotlin.math.max(8.0,fix.accuracyM+b.observedAccuracy))
            } == true
            if (boundary != null && !crossedNow) {
                clearAssumption()
                lastMph = boundary.oldMph
                rememberConfirmed(fix,accepted,boundary.oldMph,now)
                return LimitDecision(boundary.oldMph,UpcomingLimit(boundary.newMph,Geo.distance(fix.point,boundary.observed),false,roadId=boundary.toId),
                    boundaryApplied=true,reason="Saved owner boundary not yet crossed")
            }
            if (boundary != null && crossedNow) {
                crossed.add(key(boundary))
                clearAssumption();stabilizer.reset(); transition = null;candidateTransition=null; lastMatch = accepted; lastMph = boundary.newMph
                stabilizer.resolve(fix,accepted,boundary.newMph,now);rememberConfirmed(fix,accepted,boundary.newMph,now)
                return LimitDecision(boundary.newMph,boundaryApplied=true,reason="Crossed saved owner boundary")
            }
        }
        if (raw == null) {
            val inherited = assume(fix,accepted,now)
            // A lookup gap does not erase the last authoritative stabilizer anchor.
            if(inherited==null) { stabilizer.reset();transition=null;candidateTransition=null }
            return inherited ?: LimitDecision(null,reason="Unavailable: $assumptionDropReason")
        }
        val previous = lastMph
        if(accepted!=null && previous!=null && previous!=raw && lastMatch!=null && lastMatch!!.road.id!=accepted.road.id && fix.bearing!=null) {
            if(candidateTransition?.to?.road?.id!=accepted.road.id || candidateTransition?.new!=raw)
                candidateTransition=Transition(lastMatch!!,accepted,previous,raw,fix,now)
        } else candidateTransition=null
        val displayed = stabilizer.resolve(fix,accepted,raw,now)
        if (displayed != null && accepted != null && displayed == raw) {
            if (previous != null && previous != displayed && lastMatch != null &&
                lastMatch!!.road.id != accepted.road.id && fix.bearing != null && fix.accuracyM <= 25)
                transition = candidateTransition ?: Transition(lastMatch!!,accepted,previous,displayed,fix,now)
            lastMatch = accepted; lastMph = displayed; rememberConfirmed(fix,accepted,displayed,now)
            candidateTransition=null
        }
        val upcoming = raw.takeIf { accepted != null && it != displayed }?.let {
            UpcomingLimit(it,0.0,false,uncertain=accepted!!.confidence<.8 || fix.accuracyM>20 || (accepted.headingDifference ?: 90.0)>25,roadId=accepted.road.id)
        }
        val reason = when {
            fix.accuracyM > 35 -> "Unavailable: GPS accuracy exceeds 35 m"
            accepted == null && displayed == null -> "Unavailable: no confident local road match"
            upcoming != null -> "Confirming road transition"
            displayed == null -> "Unavailable: confirming a new road; prior geometry no longer fits"
            accepted == null -> "Brief match ambiguity on previous geometry"
            else -> "Confirmed local road limit"
        }
        // A large forward GPS step can exceed the stabilizer's narrow prior-road window.
        // Retain only bounded, connected evidence while the new limit is being confirmed.
        if(displayed==null && upcoming!=null) {
            assume(fix,accepted,now)?.let { return it.copy(upcoming=upcoming,reason="${it.reason}; confirming transition to $raw") }
        }
        return LimitDecision(displayed,upcoming,reason=reason)
    }
    private fun rememberConfirmed(fix: Fix,match: RoadMatch,mph: Int?,now: Long) {
        if(mph != null && match.confidence>=.7 && fix.accuracyM<=20 && fix.bearing != null &&
            (match.headingDifference ?: 90.0)<=30) {
            assumedMatch=null;assumptionDistance=0.0;assumptionPoint=null
            confirmed=ConfirmedLimit(match,mph,fix,now)
        }
    }
    private fun clearAssumption() { confirmed=null;assumedMatch=null;assumptionDistance=0.0;assumptionPoint=null }
    private fun BoundaryCorrection.mirroredFor(heading: Double?): BoundaryCorrection? {
        if(!sharedAcrossDirections || heading==null || Geo.difference(heading,bearing)<=100) return null
        return copy(fromId=toId,toId=fromId,oldMph=newMph,newMph=oldMph,bearing=(bearing+180.0)%360.0)
    }
    private fun shareable(from: Road,to: Road): Boolean {
        fun ordinary(road: Road) = road.tags["oneway"] !in setOf("yes","1","-1") &&
            road.tags["highway"]?.endsWith("_link") != true
        return ordinary(from) && ordinary(to) && from.name!=null && from.name==to.name &&
            from.tags["highway"]==to.tags["highway"] && connectedRoads(from,to)
    }
    private fun assume(fix: Fix,match: RoadMatch?,now: Long): LimitDecision? {
        val anchor=confirmed ?: return null
        val previous=assumedMatch ?: anchor.match
        fun connected(a: Road,b: Road)=a.id==b.id || listOf(a.points.first(),a.points.last()).any { p ->
            listOf(b.points.first(),b.points.last()).any { Geo.distance(p,it)<=20 } }
        val excluded=setOf("motorway","motorway_link","trunk_link","primary_link","secondary_link","tertiary_link","service","living_street")
        // Missing matching data may be bridged only on previously confirmed geometry.
        val geometry=Geo.projection(fix.point,previous.road.points)
        val effective=match ?: previous.takeIf {
            geometry.first<=kotlin.math.max(16.0,fix.accuracyM*1.5) && fix.bearing?.let { b ->
                geometry.second?.let { h -> kotlin.math.min(Geo.difference(b,h),Geo.difference(b,(h+180)%360))<=30 }
            } == true && now-anchor.at in 0..15_000
        }
        val roadType=effective?.road?.tags?.get("highway")
        // Preserve a truthful assumed limit during a brief source gap on the
        // exact same mapped road; confirmation still requires stronger evidence.
        val sameConfirmedWay=effective?.road?.id==anchor.match.road.id
        val specialTags=match?.road?.tags?.keys?.any { it.startsWith("maxspeed:") && it !in setOf("maxspeed:type") } == true
        val distance=assumptionDistance+Geo.distance(assumptionPoint ?: anchor.fix.point,fix.point)
        val dropReason=when {
            effective==null || effective.confidence < if(sameConfirmedWay) .35 else .7 -> "Assumption ended: road match lost confidence or previous geometry no longer fits"
            fix.accuracyM > (if(sameConfirmedWay) 35 else 20) || fix.bearing==null || (effective.headingDifference ?: 90.0) > (if(sameConfirmedWay) 55 else 30) -> "Assumption ended: GPS or heading uncertain"
            Geo.difference(fix.bearing,anchor.fix.bearing!!)>40 -> "Assumption ended: travel direction changed"
            now-anchor.at !in 0..90_000 -> "Assumption expired after 90 seconds"
            distance>750 -> "Assumption expired after 750 metres"
            !connected(previous.road,effective.road) -> "Assumption ended: road geometry disconnected"
            specialTags -> "Assumption ended: conditional or directional source limit"
            roadType in excluded && roadType != anchor.match.road.tags["highway"] ||
                anchor.match.road.tags["highway"] in excluded && roadType != anchor.match.road.tags["highway"] -> "Assumption ended: road context changed"
            else -> null
        }
        if(dropReason!=null) {
            clearAssumption();assumptionDropReason="$dropReason; inherited ${anchor.mph} from ${anchor.match.road.id}"
            lastMatch=null;lastMph=null;return null
        }
        assumedMatch=effective;assumptionDistance=distance;assumptionPoint=fix.point
        return LimitDecision(anchor.mph,reason="Assumed ${anchor.mph} mph from confirmed ${anchor.match.road.id}; ${match?.road?.id ?: "temporary match gap on prior geometry"}; ${now-anchor.at} ms, ${distance.toInt()} m",
            assumed=true,inheritedFrom=anchor.match.road.id)
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
            t.fix.bearing!!,t.fix.accuracyM,fix.accuracyM,t.to.confidence,t.to.distanceM,System.currentTimeMillis(),
            sharedAcrossDirections=shareable(t.from.road,t.to.road))
        // Activation is explicit only after the caller has persisted successfully.
        return result
    }
    private fun currentTransition() = pending ?: candidateTransition?.takeIf { it.to.road.id==currentRoadId }
        ?: transition?.takeIf { it.to.road.id==currentRoadId }
    fun transitionEvidence(): org.json.JSONObject? = currentTransition()?.let { t ->
        org.json.JSONObject().put("from",t.from.road.id).put("to",t.to.road.id).put("old",t.old).put("new",t.new)
            .put("predicted",RoadJson.point(t.fix.point)).put("accuracy",t.fix.accuracyM).put("at",t.at)
    }
    fun planSelection(fix: Fix,match: RoadMatch?,source: Int?,selected: Int,targetRoad: String,now: Long,
        wallNow: Long,observations: List<BoundaryObservation>): LimitSelectionPlan? {
        val row=QuickLimitCorrection.capture(fix,match,source,selected,targetRoad,now) ?: return null
        val o=observations.filter { it.applies(fix,match,wallNow) }.maxByOrNull { it.recordedAt }
        if(o!=null && selected>0) {
            if(selected==o.oldMph) return LimitSelectionPlan(observation=o.copy(stillPoint=fix.point,
                accuracy=fix.accuracyM),kind="boundary observation",message="$selected confirmed here")
            // A distinct observed limit must agree with the local upcoming source and progress
            // farther than both observations' uncertainty. Same-point taps never invent a boundary.
            if(selected==source && match!!.road.id!=o.from.id &&
                passedBoundary(fix,o.stillPoint,o.bearing) &&
                Geo.distance(o.stillPoint,fix.point)>kotlin.math.max(8.0,o.accuracy+fix.accuracyM)) {
                val b=BoundaryCorrection(o.from.id,match.road.id,o.oldMph,selected,o.predicted,fix.point,
                    o.bearing,o.predictedAccuracy,fix.accuracyM,match.confidence,match.distanceM,wallNow,
                    viaIds=if(match.road.id!=o.to.id) listOf(o.to.id) else emptyList(),stillPoint=o.stillPoint,
                    sharedAcrossDirections=match.road.id==o.to.id && shareable(o.from,o.to))
                return LimitSelectionPlan(boundary=b,consumed=o,kind="boundary correction",message="$selected starts here")
            }
            // Ambiguous second tap is not permission to overwrite the upcoming road.
            return null
        }
        val t=currentTransition()?.takeIf { now-it.at in 0..120_000 && match!!.road.id==it.to.road.id &&
            Geo.distance(it.fix.point,fix.point)<=600 && fix.bearing?.let { b -> Geo.difference(b,it.fix.bearing!!)<=35 }==true &&
            connectedRoads(it.from.road,it.to.road) }
        if(t!=null && selected==t.old) {
            val observation=BoundaryObservation(t.from.road,t.to.road,t.old,t.new,t.fix.point,fix.point,
                fix.bearing!!,t.fix.accuracyM,fix.accuracyM,wallNow)
            return LimitSelectionPlan(observation=observation,kind="boundary observation",message="$selected confirmed here")
        }
        if(t!=null && selected==t.new && selected==source) startsHere(fix,now)?.let {
            return LimitSelectionPlan(boundary=it.copy(recordedAt=wallNow),kind="boundary correction",message="$selected starts here")
        }
        return LimitSelectionPlan(override=row,consumed=o,kind="road limit override",message=when(selected) {
            OWNER_UNKNOWN -> "Unknown confirmed here"
            OWNER_NATIONAL -> "National limit confirmed here"
            else -> "$selected confirmed here"
        })
    }
    fun canMarkBoundary(now: Long) = reportable && (currentMatch?.confidence ?: 0.0)>=.7 && currentTransition()?.let {
        now-it.at in 0..120_000 && currentRoadId==it.to.road.id
    } == true
    fun startsHere(fix: Fix,now: Long): BoundaryCorrection? {
        if(pending!=null) return changedNow(fix,now)
        val t=currentTransition() ?: return null
        if(!canMarkBoundary(now) || fix.accuracyM>20 || fix.bearing==null ||
            now-fix.elapsedMs !in 0..5000 || Geo.difference(fix.bearing,t.fix.bearing!!)>40 ||
            Geo.distance(t.fix.point,fix.point)>2000 || (currentMatch?.confidence ?: 0.0)<.7 ||
            (currentMatch?.headingDifference ?: 90.0)>30 || Geo.projection(fix.point,t.to.road.points).first>25 ||
            listOf(t.from.road.points.first(),t.from.road.points.last()).none { p ->
                listOf(t.to.road.points.first(),t.to.road.points.last()).any { Geo.distance(p,it)<=20 }
            }) return null
        return BoundaryCorrection(t.from.road.id,t.to.road.id,t.old,t.new,t.fix.point,fix.point,
            t.fix.bearing!!,t.fix.accuracyM,fix.accuracyM,currentMatch!!.confidence,currentMatch!!.distanceM,System.currentTimeMillis(),
            sharedAcrossDirections=shareable(t.from.road,t.to.road))
    }
    fun feedbackSaved() { cancelFeedback();clearAssumption(); transition = null; stabilizer.reset(); lastMatch = null; lastMph = null }
    fun cancelFeedback() { pending=null; pendingFeedback=false; transition=null;candidateTransition=null; stabilizer.reset() }
    fun reset() { crossed.clear();cancelFeedback();clearAssumption(); lastMatch=null; lastMph=null; currentRoadId=null }
}
