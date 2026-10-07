package uk.co.traynor.speedbuddy

/** Deterministic harness around the same [DrivingLimitPipeline] and [DriveBus] used in driving. */
internal data class ReplayFrame(
    val fix: Fix, val roads: List<Road>, val overrides: List<RoadDb.Override> = emptyList(),
    val corrections: Map<String,RoadLimitCorrection> = emptyMap(), val boundaries: List<BoundaryCorrection> = emptyList(),
    val observations: List<BoundaryObservation> = emptyList(), val regional: RegionalPackMatcher.Result? = null,
    val live: LiveRoadState? = null, val now: Long = fix.elapsedMs, val wallNow: Long = now,
)
internal class DrivingReplay(private val pipeline: DrivingLimitPipeline = DrivingLimitPipeline()) {
    fun play(frame: ReplayFrame): DriveState {
        val result=pipeline.evaluate(frame.fix,frame.roads,frame.overrides,frame.corrections,frame.boundaries,frame.observations,
            frame.now,frame.wallNow,frame.regional,frame.live)
        // Only active MobileReport instances are Cameras; known enforcement zones remain passive context.\n        val (alert,cameraDecision)=cameraDetector.evaluate(frame.fix,result.road,frame.cameras,frame.speedMph,frame.roads,\n            frame.wallNow,result.decision.mph)\n        val state=result.applyTo(DriveBus.state.value.copy(active=true,speedMph=frame.speedMph)).copy(\n            alert=alert,decision=cameraDecision,mobileEnforcementZones=frame.mobileEnforcementZones)
        DriveBus.set(state)
        return state
    }
}
