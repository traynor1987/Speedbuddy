package uk.co.traynor.speedbuddy

/** Deterministic harness around the same [DrivingLimitPipeline] and [DriveBus] used in driving. */
internal data class ReplayFrame(
    val fix: Fix, val roads: List<Road>, val overrides: List<RoadDb.Override> = emptyList(),
    val corrections: Map<String,RoadLimitCorrection> = emptyMap(), val boundaries: List<BoundaryCorrection> = emptyList(),
    val observations: List<BoundaryObservation> = emptyList(), val regional: RegionalPackMatcher.Result? = null,
    val live: LiveRoadState? = null, val cameras: List<Camera> = emptyList(),
    val mobileEnforcementZones: List<MobileEnforcementZone> = emptyList(),
    val speedMph: Double? = fix.speedMps?.times(MPS_TO_MPH), val now: Long = fix.elapsedMs, val wallNow: Long = now,
)
internal class DrivingReplay(private val pipeline: DrivingLimitPipeline = DrivingLimitPipeline()) {
    private val cameraDetector=CameraApproachDetector()
    fun play(frame: ReplayFrame): DriveState {
        val result=pipeline.evaluate(frame.fix,frame.roads,frame.overrides,frame.corrections,frame.boundaries,frame.observations,
            frame.now,frame.wallNow,frame.regional,frame.live)
        // Only active MobileReport instances are Cameras; known enforcement zones remain passive context.
        val (alert,cameraDecision)=cameraDetector.evaluate(frame.fix,result.road,frame.cameras,frame.speedMph,frame.roads,
            frame.wallNow,result.decision.mph)
        val state=result.applyTo(DriveState(active=true,speedMph=frame.speedMph)).copy(
            alert=alert,decision=cameraDecision,mobileEnforcementZones=frame.mobileEnforcementZones)
        DriveBus.set(state)
        return state
    }
}
