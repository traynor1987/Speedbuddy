package uk.co.traynor.speedbuddy

import org.json.JSONObject

/** Drive mode and regressions share matching, source selection, owner priority and UI mapping. */
internal class DrivingLimitPipeline(val engine: LimitDecisionEngine = LimitDecisionEngine(),
    val matcher: RoadMatcher = RoadMatcher()) {
    private val upcomingDetector=UpcomingLimitDetector()
    private data class Preview(val upcoming: UpcomingLimit,val current: Int?,val roadId: String?,val fix: Fix,val at: Long)
    private var preview: Preview? = null
    private var latestEvaluatedAt: Long? = null
    private var regionalGeneration: String?=null
    private var generationObserved=false
    private var previousWayDirection: Pair<String,WayTravelDirection>? = null
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
        if(now-fix.elapsedMs !in 0..5000 || latestEvaluatedAt?.let { fix.elapsedMs<it }==true) return DriveLimitResult(fix,null,null,
            LimitDecision(null,reason="Unavailable: stale GPS fix; decision history unchanged"),null)
        // A covered regional pack is authoritative even when it says Unknown or uncertain.
        // Legacy cache is only considered when no usable regional provider participated.
        latestEvaluatedAt=fix.elapsedMs
        if(regional!=null) {
            if(generationObserved && regionalGeneration!=regional.generation) {
                engine.reset();matcher.reset();preview=null;previousWayDirection=null
            }
            regionalGeneration=regional.generation;generationObserved=true
        }
        val covered=regional!=null && regional.state !in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE)
        val contextualRoads=(if(covered) regional?.candidates.orEmpty()+roads else roads+regional?.candidates.orEmpty())
            .map(RoadIdentity::road).distinctBy { it.id }
        val road=if(covered) regional?.match?.let { it.copy(road=RoadIdentity.road(it.road)) } else matcher.match(fix,contextualRoads)
        val regionalState=regional?.let { result -> LiveRoadState(result.state,result.match!=null,
            if(result.state==RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN) result.match?.let { PackSpeedLimits.mph(it.road.tags,fix.bearing,it) } else null,
            result.state.permitsOverpass) }
        val cached=if(regionalState==null || regionalState.state in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE))
            road?.takeIf { it.confidence>=.35 }?.let { PackSpeedLimits.mph(it.road.tags,fix.bearing,it) } else null
        // Owner corrections remain inside LimitDecisionEngine; this resolver selects
        // only the non-owner authority and keeps Unknown/uncertain terminal.
        val safeLive=live?.takeIf { it.state in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE,RoadProviderState.ROAD_MATCH_UNCERTAIN) || road!=null && it.roadId!=null && RoadIdentity.same(it.roadId,road.road.id) && road.confidence>=.7 && fix.accuracyM<=20 &&
            fix.bearing!=null && (road.headingDifference ?: 90.0)<=30 && it.matched } ?: live?.takeIf { it.state !in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE) }?.let {
            LiveRoadState(RoadProviderState.ROAD_MATCH_UNCERTAIN,false,null,false,error="Live road identity does not agree with fresh local geometry")
        }
        val resolved=RoadProviderResolver.resolveProviderStates(null,regionalState,cached,safeLive)
        if(resolved.source==RoadSource.LIVE && resolved.state==RoadProviderState.ROAD_MATCH_UNCERTAIN) {
            // Conflicting identities cannot authorize owner rules, assumed continuity or geometry-dependent alerts.
            engine.reset();matcher.reset();preview=null;previousWayDirection=null
            val reason=safeLive?.error ?: "Live road selection uncertain"
            return DriveLimitResult(fix,null,null,LimitDecision(null,reason="Unavailable: $reason"),null,
                contextualRoads,false,RoadDataDiagnostics("Live server",resolved.state.name,
                    when(regional?.coverage) { true -> "Covered";false -> "Not covered";null -> "Not established" },
                    sampleElapsedMs=fix.elapsedMs,error=reason,contextComplete=false))
        }
        val source=resolved.limitMph
        val direction=road?.takeIf { it.confidence>=.7 && fix.accuracyM<=20 &&
            (it.headingDifference ?: 90.0)<=30 }?.let { WayTravelDirection.from(it,fix.bearing) }
        val previous=previousWayDirection
        if(road!=null && direction!=null && previous!=null && RoadIdentity.same(road.road.id,previous.first) &&
            road.road.tags.keys.any { it.startsWith("maxspeed:forward") || it.startsWith("maxspeed:backward") } &&
            direction!=previous.second) {
            // Opposite-direction limits are separate authorities, never a same-road transition
            // whose identical endpoints must be crossed before the lower limit can display.
            engine.reset();preview=null
        }
        if(road!=null && direction!=null) previousWayDirection=road.road.id to direction
        val ownerRoad=road?.takeIf { it.confidence>=.7 && fix.accuracyM<=20 &&
            if(fix.bearing!=null) (it.headingDifference ?: 90.0)<=30
            else fix.accuracyM<=8 && it.distanceM<=fix.accuracyM && it.confidence>=.85 }
        val owner=OwnerRoadLimits.select(ownerRoad?.road,fix.bearing,overrides,corrections,fix.point)
        val decision=engine.decide(fix,road,source,owner?.mph,boundaries.map(RoadIdentity::boundary),now,observations.map(RoadIdentity::observation),wallNow).let {
            if(owner?.national==true || !it.ownerApplied && road!=null && source==it.mph &&
                PackSpeedLimits.national(road.road.tags,fix.bearing,road)) it.copy(national=true) else it
        }
        val corrected=contextualRoads.map { corrections[it.id]?.apply(it) ?: it }
        val geometryComplete=!covered || regional?.contextComplete==true
        val detected=decision.upcoming ?: if(geometryComplete) upcomingDetector.detect(fix,road,decision.mph,corrected) else null
        val prior=preview
        val upcoming=detected ?: prior?.takeIf {
            geometryComplete && decision.assumed && decision.mph==it.current && now-it.at in 0..15_000 &&
                (road==null || road.road.id in listOf(it.roadId,it.upcoming.roadId)) &&
                Geo.distance(fix.point,it.fix.point)<=150 && fix.bearing?.let { b ->
                    it.fix.bearing?.let { h -> Geo.difference(b,h)<=35 }
                }==true
        }?.let { it.upcoming.copy(distanceM=(it.upcoming.distanceM-Geo.distance(it.fix.point,fix.point)).coerceAtLeast(0.0),uncertain=true) }
        if(detected!=null) preview=Preview(detected,decision.mph,road?.road?.id,fix,now)
        else if(upcoming==null) preview=null
        val provider=when { covered -> "Regional offline";road==null -> "None";resolved.source!=RoadSource.LIVE -> "Legacy saved OSM";else -> "Live server with legacy geometry" }
        val fallback=if(covered || resolved.source==RoadSource.LIVE) null else when {
            live!=null -> live.error ?: "Live ${live.state.name}; using saved legacy geometry"
            else -> when(regional?.state) {
            RoadProviderState.SERVICE_UNAVAILABLE -> "Regional packs unavailable"
            RoadProviderState.COVERAGE_UNAVAILABLE -> "No regional coverage at this position"
            else -> "Live request pending or unavailable; saved legacy geometry used when reliable"
        }
        }
        val details=RoadDataDiagnostics(provider,
            if(covered) regional!!.state.name else if(road!=null) (safeLive?.state?.takeIf { resolved.source==RoadSource.LIVE }?.name ?: "LEGACY_MATCHED") else "NO_LOCAL_MATCH",
            when(regional?.coverage) { true -> "Covered";false -> "Not covered";null -> "Not established" },
            regional?.packDetails.orEmpty().map { RegionalPackInfo(it.displayName,it.id,it.version,it.osmTimestamp) },
            fix.elapsedMs,fallback,regional?.error ?: safeLive?.error ?: live?.error ?: live?.takeIf { it.state==RoadProviderState.SERVICE_UNAVAILABLE }?.let { "Live road service unavailable" },
            regional?.contextComplete)
        return DriveLimitResult(fix,road,source,decision,upcoming,corrected,geometryComplete,details)
    }
}
internal data class DriveLimitResult(val fix: Fix,val road: RoadMatch?,val source: Int?,
    val decision: LimitDecision,val upcoming: UpcomingLimit?,val contextualRoads: List<Road> = emptyList(),val geometryComplete: Boolean=true,val roadData: RoadDataDiagnostics=RoadDataDiagnostics()) {
    fun applyTo(state: DriveState): DriveState {
        if(state.fix?.let { it.elapsedMs>fix.elapsedMs }==true) return state
        return state.copy(roadData=roadData,fix=fix,roadDecisionElapsedMs=fix.elapsedMs,pendingConfirmedLimit=false,road=road,sourceLimitMph=source,limitMph=decision.mph,
        limitDecision=decision,upcoming=upcoming,status=when {
            decision.mph==null -> "Road limit unknown"
            state.speedMph==null && state.active -> "GPS speed unavailable"
            else -> ""
        })
    }
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
            .put("roadData",state.roadData.json(state.road?.confidence))
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
