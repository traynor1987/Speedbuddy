package uk.co.traynor.speedbuddy

import org.json.JSONObject

/** Drive mode and regressions share matching, source selection, owner priority and UI mapping. */
internal class DrivingLimitPipeline(val engine: LimitDecisionEngine = LimitDecisionEngine(),
    val matcher: RoadMatcher = RoadMatcher()) {
    private val limits: SpeedLimitProvider=OsmSpeedLimitProvider()
    private val upcomingDetector=UpcomingLimitDetector()
    private data class Preview(val upcoming: UpcomingLimit,val current: Int?,val roadId: String?,val fix: Fix,val at: Long)
    private var preview: Preview? = null
    /** Called only after the owner's selection was durably saved. */
    fun acceptSavedSelection(plan: LimitSelectionPlan?) {
        if(plan?.observation==null) engine.reset()
        // This tap is direct current-position evidence for this pass. Persisted
        // replay still requires crossing the GPS uncertainty margin on future passes.
        plan?.boundary?.let(engine::confirmSavedBoundary)
    }
    fun evaluate(fix: Fix,roads: List<Road>,overrides: List<RoadDb.Override>,corrections: Map<String,RoadLimitCorrection>,
        boundaries: List<BoundaryCorrection>,observations: List<BoundaryObservation>,now: Long,wallNow: Long,
        regional: RegionalPackMatcher.Result? = null, live: LiveRoadState? = null): DriveLimitResult {
        // Reject before either matcher or engine can mutate state after delayed IO.
        if(now-fix.elapsedMs !in 0..5000) return DriveLimitResult(fix,null,null,
            LimitDecision(null,reason="Unavailable: stale GPS fix; decision history unchanged"),null)
        // A covered regional pack is authoritative even when it says Unknown or uncertain.
        // Legacy cache is only considered when no usable regional provider participated.
        val contextualRoads=(roads+regional?.candidates.orEmpty()).distinctBy { it.id }
        val road=regional?.match ?: matcher.match(fix,contextualRoads)
        val regionalState=regional?.let { result -> LiveRoadState(result.state,result.match!=null,
            if(result.state==RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN) result.match?.let { PackSpeedLimits.mph(it.road.tags,fix.bearing,it) } else null,
            result.state.permitsOverpass) }
        val cached=if(regionalState==null || regionalState.state in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE)) limits.limit(road) else null
        // Owner corrections remain inside LimitDecisionEngine; this resolver selects
        // only the non-owner authority and keeps Unknown/uncertain terminal.
        val source=RoadProviderResolver.resolveProviderStates(null,regionalState,cached,live).limitMph
        val owner=OwnerRoadLimits.select(road?.road,fix.bearing,overrides,corrections,fix.point)
        val decision=engine.decide(fix,road,source,owner?.mph,boundaries,now,observations,wallNow).let {
            if(owner?.national==true) it.copy(national=true) else it
        }
        val corrected=contextualRoads.map { corrections[it.id]?.apply(it) ?: it }
        val detected=decision.upcoming ?: upcomingDetector.detect(fix,road,decision.mph,corrected)
        val prior=preview
        val upcoming=detected ?: prior?.takeIf {
            decision.assumed && decision.mph==it.current && now-it.at in 0..15_000 &&
                (road==null || road.road.id in listOf(it.roadId,it.upcoming.roadId)) &&
                Geo.distance(fix.point,it.fix.point)<=150 && fix.bearing?.let { b ->
                    it.fix.bearing?.let { h -> Geo.difference(b,h)<=35 }
                }==true
        }?.let { it.upcoming.copy(distanceM=(it.upcoming.distanceM-Geo.distance(it.fix.point,fix.point)).coerceAtLeast(0.0),uncertain=true) }
        if(detected!=null) preview=Preview(detected,decision.mph,road?.road?.id,fix,now)
        else if(upcoming==null) preview=null
        return DriveLimitResult(fix,road,source,decision,upcoming)
    }
}
internal data class DriveLimitResult(val fix: Fix,val road: RoadMatch?,val source: Int?,
    val decision: LimitDecision,val upcoming: UpcomingLimit?) {
    fun applyTo(state: DriveState)=state.copy(fix=fix,road=road,sourceLimitMph=source,limitMph=decision.mph,
        limitDecision=decision,upcoming=upcoming,status=when {
            decision.mph==null -> "Road limit unknown"
            state.speedMph==null && state.active -> "GPS speed unavailable"
            else -> ""
        })
}

/** Bounded diagnostic receipts; none of these fields appear in the normal driving controls. */
internal object LimitDiagnostics {
    private fun observationReceipt(o: BoundaryObservation)=RoadJson.observation(o)
        .put("from",o.from.id).put("to",o.to.id)
    fun snapshot(kind: String,state: DriveState,selected: Int? = null,plan: LimitSelectionPlan? = null,
        transition: JSONObject? = null): String {
        val fix=state.fix
        val decision=state.limitDecision
        return JSONObject().put("kind",kind).put("at",System.currentTimeMillis())
            .put("displayed",state.limitMph ?: JSONObject.NULL)
            .put("state",when { decision?.assumed==true -> "assumed";decision?.ownerApplied==true -> "owner";state.limitMph==null -> "unknown";else -> "confirmed" })
            .put("selected",selected ?: JSONObject.NULL).put("location",fix?.point?.let(RoadJson::point) ?: JSONObject.NULL)
            .put("accuracy",fix?.accuracyM ?: JSONObject.NULL).put("heading",fix?.bearing ?: JSONObject.NULL)
            .put("elapsed",fix?.elapsedMs ?: JSONObject.NULL).put("road",state.road?.road?.id ?: JSONObject.NULL)
            .put("confidence",state.road?.confidence ?: JSONObject.NULL)
            .put("matchDistance",state.road?.distanceM ?: JSONObject.NULL)
            .put("source",state.sourceLimitMph ?: JSONObject.NULL)
            .put("upcoming",state.upcoming?.mph ?: JSONObject.NULL)
            .put("upcomingDistance",state.upcoming?.distanceM ?: JSONObject.NULL)
            .put("upcomingRoad",state.upcoming?.roadId ?: JSONObject.NULL)
            .put("roadRequest",state.roadRequestKind)
            .put("subdivisionLevel",state.subdivisionLevel ?: JSONObject.NULL)
            .put("currentRegionStatus",state.currentRegionStatus)
            .put("completedRoadRegions",state.completedRoadRegions)
            .put("transition",transition ?: JSONObject.NULL)
            .put("classification",plan?.kind ?: JSONObject.NULL)
            .put("observation",plan?.observation?.let(::observationReceipt) ?: JSONObject.NULL)
            .put("boundary",plan?.boundary?.let(RoadJson::boundary) ?: JSONObject.NULL)
            .put("consumedObservation",plan?.consumed?.let(::observationReceipt) ?: JSONObject.NULL)
            .put("reason",decision?.reason ?: state.status)
            .put("inheritedFrom",decision?.inheritedFrom ?: JSONObject.NULL).toString()
    }
}
