package uk.co.traynor.speedbuddy

/** Briefly preserve existing speech only while the new fix still fits confirmed geometry. */
internal object PendingDrivingEvidence {
    const val reason = "Awaiting fresh road match"
    fun anchor(state: DriveState): Fix?=state.pendingLimitAnchor ?: state.fix?.let { fix ->
        state.limitDecision?.let { d -> if(d.evidenceElapsedMs!=null && d.evidencePoint!=null)
            fix.copy(point=d.evidencePoint,elapsedMs=d.evidenceElapsedMs) else fix } ?: fix
    }
    fun distance(state: DriveState)=if(state.pendingLimitAnchor!=null) state.pendingLimitDistanceM else state.limitDecision?.evidenceDistanceM ?: 0.0
    fun fits(prior: DriveState, fix: Fix): Boolean {
        val road=prior.road ?: return false
        val anchor=anchor(prior) ?: return false
        val travelled=distance(prior)+Geo.distance(prior.fix?.point ?: anchor.point,fix.point)
        if(fix.elapsedMs-anchor.elapsedMs !in 0..2000 || travelled>30.0) return false
        if(prior.roadDecisionElapsedMs?.let { fix.elapsedMs-it in 0..2000 }!=true ||
            fix.accuracyM !in 1.0..25.0 || road.confidence<.7 ||
            Geo.projection(fix.point,road.road.points).first>maxOf(10.0,fix.accuracyM*1.5)) return false
        val before=prior.fix?.bearing
        return if(fix.bearing!=null && before!=null) Geo.difference(fix.bearing,before)<=30 && anchor.bearing?.let { Geo.difference(fix.bearing,it)<=30 }!=false
            else (fix.speedMps ?: Double.MAX_VALUE)<=2.0 && road.road.tags.keys.none {
                it.startsWith("maxspeed:forward") || it.startsWith("maxspeed:backward") }
    }
    fun current(state: DriveState, now: Long = state.fix?.elapsedMs ?: 0): Boolean = state.pendingConfirmedLimit &&
        state.fix?.let { fits(state,it) && state.roadDecisionElapsedMs!=it.elapsedMs &&
            state.roadDecisionElapsedMs?.let { at -> now-at in 0..2000 }==true }==true
    fun cameraLimit(state: DriveState, now: Long = state.fix?.elapsedMs ?: 0): Int? = state.alert?.let {
        CameraLimits.resolve(it.camera,state.road,state.limitMph.takeUnless {
            state.limitDecision?.assumed==true && !current(state,now)
        })
    }
    fun limitSpeechRelevant(state: DriveState, limit: Int, now: Long = state.fix?.elapsedMs ?: 0) = state.active && state.limitMph==limit &&
        (state.limitDecision?.assumed!=true || current(state,now))
}
