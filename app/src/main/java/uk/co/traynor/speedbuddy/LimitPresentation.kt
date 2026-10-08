package uk.co.traynor.speedbuddy

/** Conservative warning recovery and a numeric-free, bounded junction presentation. */
internal class LimitPresentation {
    private data class Anchor(val match: RoadMatch,val fix: Fix)
    private var anchor: Anchor?=null
    private var latest: Long?=null
    private var changingSince: Long?=null
    private var consumedJunction: GeoPoint?=null
    private var cautionMph: Int?=null
    private var recoverySince: Long?=null
    private var recoveryFix: Long?=null

    fun noteAssumption(decision: LimitDecision?) {
        if(decision?.assumed==true && decision.mph!=null) {
            // Every intervening uncertainty restarts recovery, including a pending
            // GPS request. Otherwise completed 1 Hz requests flash confirmed again.
            cautionMph=decision.mph;recoverySince=null;recoveryFix=null
        }
    }
    fun resolve(fix: Fix,match: RoadMatch?,decision: LimitDecision,roads: List<Road>,complete: Boolean): LimitDecision {
        if(latest?.let { fix.elapsedMs<it }==true) return LimitDecision(null,reason="Unavailable: stale presentation frame")
        latest=fix.elapsedMs
        val prior=anchor
        val credible=complete && prior!=null && fix.elapsedMs-prior.fix.elapsedMs in 0..10_000 &&
            fix.accuracyM in 1.0..20.0 && fix.bearing!=null && prior.fix.bearing!=null &&
            (fix.speedMps ?: 0.0)>=2.0 && Geo.distance(prior.fix.point,fix.point)>=8.0
        val junction=if(credible) listOf(prior!!.match.road.points.first(),prior.match.road.points.last()).firstOrNull { j ->
            Geo.distance(j,fix.point)<=45 && Geo.distance(prior.fix.point,j)<=80 &&
                roads.any { next -> next.id!=prior.match.road.id &&
                    RoadLookAhead.outgoing(next,j)?.let { heading ->
                        Geo.difference(heading,fix.bearing!!)<=35 && Geo.projection(fix.point,next.points).first<=maxOf(12.0,fix.accuracyM*1.5)
                    }==true } &&
                (passedBoundary(fix,j,prior.fix.bearing!!) ||
                    Geo.difference(fix.bearing!!,prior.fix.bearing!!)>=45 &&
                    Geo.projection(fix.point,prior.match.road.points).first>maxOf(8.0,fix.accuracyM))
        } else null
        val unresolved=decision.mph==null || decision.assumed ||
            decision.upcoming!=null && !decision.ownerApplied && !decision.boundaryApplied
        if(unresolved && junction!=null && consumedJunction?.let { Geo.distance(it,junction)<20 }!=true && changingSince==null) {
            changingSince=fix.elapsedMs;consumedJunction=junction
        }
        val started=changingSince
        if(unresolved && started!=null) {
            cautionMph=null;recoverySince=null
            if(fix.elapsedMs-started in 0 until 5_000 && fix.accuracyM<=20 && fix.bearing!=null)
                return LimitDecision(null,reason="Resolving connected road transition",changing=true,changingUntilElapsedMs=started+5_000)
            changingSince=null;anchor=null
            return LimitDecision(null,reason="Unavailable: road transition unresolved after presentation window")
        }
        if(decision.mph==null) { cautionMph=null;recoverySince=null;return decision }
        if(!decision.assumed) {
            changingSince=null
            if(match!=null && match.confidence>=.7 && fix.accuracyM<=20) {
                anchor=Anchor(match,fix)
                if(consumedJunction?.let { Geo.distance(it,fix.point)>80 }==true) consumedJunction=null
            }
        }
        if(decision.assumed) { noteAssumption(decision);return decision }
        if(cautionMph!=decision.mph) { cautionMph=null;recoverySince=null;recoveryFix=null;return decision }
        if(recoverySince==null) { recoverySince=fix.elapsedMs;recoveryFix=fix.elapsedMs }
        if(fix.elapsedMs-recoverySince!!>=1_000 && recoveryFix!=fix.elapsedMs) {
            cautionMph=null;recoverySince=null;recoveryFix=null;return decision
        }
        // Numeric authority comes exclusively from this fresh decision, never from this latch.
        return decision.copy(assumed=true,reason="Assumed presentation: awaiting sustained confidence recovery; ${decision.reason}")
    }
    fun reset() { anchor=null;latest=null;changingSince=null;consumedJunction=null;cautionMph=null;recoverySince=null;recoveryFix=null }
}
