package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

/** Real matcher -> source provider -> Drive decision, including gaps the helper tests missed. */
class PhysicalRoadRegressionTest {
    private val p=GeoPoint(53.0,-2.0)
    private fun road(id: String, a: Double, b: Double, mph: Int?)=Road(id,"Test road",
        listOf(Geo.ahead(p,0.0,a),Geo.ahead(p,0.0,b)),mapOf("highway" to "primary")+
            (mph?.let { mapOf("maxspeed" to "$it mph") } ?: emptyMap()))
    private val old=road("old",0.0,200.0,60)
    private val next=road("next",200.0,1500.0,40)
    private fun fix(m: Double,t: Long,accuracy: Double=5.0)=Fix(Geo.ahead(p,0.0,m),accuracy,20.0,1.0,0.0,t)
    private fun drive(e: LimitDecisionEngine, matcher: RoadMatcher, m: Double,t: Long,roads: List<Road>): LimitDecision {
        val result=DrivingLimitPipeline(e,matcher).evaluate(fix(m,t),roads,emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t)
        val state=result.applyTo(DriveState())
        assertEquals(result.decision.mph,state.limitMph)
        return result.decision.copy(upcoming=state.upcoming)
    }
    @Test fun knownMatchGapSameKnownNeverFlashesUnknown() {
        val e=LimitDecisionEngine();val matcher=RoadMatcher()
        assertEquals(60,drive(e,matcher,100.0,1000,listOf(old)).mph)
        val gap=drive(e,matcher,110.0,2000,emptyList())
        assertEquals(60,gap.mph);assertTrue(gap.assumed)
        val restored=drive(e,matcher,120.0,3000,listOf(old))
        assertEquals(60,restored.mph);assertFalse(restored.assumed)
    }
    @Test fun `same-road geometry gap remains assumed beyond the old fifteen second matcher window`() {
        val e=LimitDecisionEngine();val matcher=RoadMatcher()
        assertEquals(20,drive(e,matcher,100.0,1_000,listOf(old.copy(tags=old.tags+mapOf("maxspeed" to "20 mph")))).mph)
        val gap=drive(e,matcher,120.0,21_000,emptyList())
        assertEquals(20,gap.mph)
        assertTrue(gap.assumed)
    }
    @Test fun compatibleSameRoadSourceGapAtNormalGpsAccuracyStaysAssumed() {
        val engine=LimitDecisionEngine();val matcher=RoadMatcher()
        val confirmed=road("same",0.0,1_000.0,30)
        val sourceGap=confirmed.copy(tags=mapOf("highway" to "primary"))
        assertEquals(30,drive(engine,matcher,100.0,1_000,listOf(confirmed)).mph)
        val gap=DrivingLimitPipeline(engine,matcher).evaluate(fix(120.0,2_000,24.0),listOf(sourceGap),emptyList(),emptyMap(),emptyList(),emptyList(),2_000,12_000).applyTo(DriveState())
        assertEquals(30,gap.limitMph)
        assertTrue(gap.limitDecision!!.assumed)
        assertFalse(gap.status.contains("unknown",ignoreCase=true))
        val restored=drive(engine,matcher,140.0,3_000,listOf(confirmed))
        assertEquals(30,restored.mph);assertFalse(restored.assumed)
    }
    @Test fun transitionAfterGapRetainsCurrentAndPreviewsFortyUntilConfirmation() {
        val e=LimitDecisionEngine();val matcher=RoadMatcher()
        drive(e,matcher,180.0,1000,listOf(old,next))
        assertEquals(60,drive(e,matcher,190.0,2000,emptyList()).mph)
        val candidate=drive(e,matcher,225.0,3000,listOf(old,next))
        assertEquals(60,candidate.mph);assertEquals(40,candidate.upcoming?.mph)
        drive(e,matcher,230.0,4000,listOf(old,next))
        assertEquals(40,drive(e,matcher,250.0,5000,listOf(old,next)).mph)
    }
    @Test fun longGpsStepAtBoundaryDoesNotEraseTrustworthyPrior() {
        val e=LimitDecisionEngine();val matcher=RoadMatcher()
        drive(e,matcher,180.0,1000,listOf(old,next))
        val candidate=drive(e,matcher,360.0,2000,listOf(old,next))
        assertEquals(60,candidate.mph);assertTrue(candidate.assumed);assertEquals(40,candidate.upcoming?.mph)
    }
    @Test fun matchGapCannotInventLimitOutsidePriorGeometry() {
        val e=LimitDecisionEngine();val matcher=RoadMatcher()
        drive(e,matcher,180.0,1000,listOf(old))
        assertNull(drive(e,matcher,400.0,2000,emptyList()).mph)
    }
    @Test fun advanceWarningSurvivesBriefMatchedSourceGap() {
        val pipeline=DrivingLimitPipeline()
        val initial=pipeline.evaluate(fix(100.0,1000),listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),1000,11_000)
        assertEquals(40,initial.upcoming?.mph)
        val gap=pipeline.evaluate(fix(110.0,2000),emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),2000,12_000)
        assertEquals(60,gap.decision.mph);assertTrue(gap.decision.assumed)
        assertEquals(40,gap.upcoming?.mph)
    }
    @Test fun staleFixDoesNotMutateProductionCurrentRoadOrBoundaryHistory() {
        val pipeline=DrivingLimitPipeline()
        pipeline.evaluate(fix(180.0,1000),listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),1000,11_000)
        val stale=pipeline.evaluate(fix(260.0,1000),listOf(old,next),listOf(RoadDb.Override(next.id,0.0,40)),emptyMap(),emptyList(),emptyList(),7000,17_000)
        assertNull(stale.decision.mph);assertTrue(stale.decision.reason.contains("stale"))
        val freshGap=pipeline.evaluate(fix(190.0,8000),emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),8000,18_000)
        assertEquals(60,freshGap.decision.mph);assertTrue(freshGap.decision.assumed)
    }
    @Test fun takingConnectedUnknownBranchDoesNotRetainAbandonedRoadPreview() {
        val junction=Geo.ahead(p,0.0,200.0)
        val branch=Road("branch","Branch",listOf(junction,Geo.ahead(junction,20.0,400.0)),mapOf("highway" to "primary"))
        val pipeline=DrivingLimitPipeline()
        assertEquals(40,pipeline.evaluate(fix(160.0,1000),listOf(old,next,branch),emptyList(),emptyMap(),emptyList(),emptyList(),1000,11_000).upcoming?.mph)
        val turned=fix(230.0,2000).copy(point=Geo.ahead(junction,20.0,30.0),bearing=20.0)
        val result=pipeline.evaluate(turned,listOf(old,next,branch),emptyList(),emptyMap(),emptyList(),emptyList(),2000,12_000)
        assertEquals(branch.id,result.road?.road?.id);assertEquals(60,result.decision.mph)
        assertNull(result.upcoming)
    }
    @Test fun singlePickerConfirmationDoesNotOverrideEntireLongRoad() {
        val longRoad=road("long",0.0,1500.0,30)
        val observation=RoadDb.Override(longRoad.id,0.0,20,30,fix(100.0,1000).point,11_000,5.0)
        val pipeline=DrivingLimitPipeline()
        val here=pipeline.evaluate(fix(100.0,1000),listOf(longRoad),listOf(observation),emptyMap(),emptyList(),emptyList(),1000,11_000)
        assertEquals(20,here.decision.mph)
        val elsewhere=DrivingLimitPipeline().evaluate(fix(500.0,2000),listOf(longRoad),listOf(observation),emptyMap(),emptyList(),emptyList(),2000,12_000)
        assertEquals(30,elsewhere.decision.mph);assertFalse(elsewhere.decision.ownerApplied)
    }
    @Test fun briefGapAfterOwnerStillHereInheritsOwnerCurrentRatherThanPrematureSource() {
        val pipeline=DrivingLimitPipeline()
        for((m,t) in listOf(180.0 to 1000L,225.0 to 2000L,240.0 to 3000L,255.0 to 4000L))
            pipeline.evaluate(fix(m,t),listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t)
        val o=pipeline.engine.planSelection(fix(260.0,4500),RoadMatch(next,0.0,0.0,.95),40,60,next.id,4500,14_500,emptyList())!!.observation!!
        assertEquals(60,pipeline.evaluate(fix(270.0,5000),listOf(old,next),emptyList(),emptyMap(),emptyList(),listOf(o),5000,15_000).decision.mph)
        val gap=pipeline.evaluate(fix(280.0,6000),emptyList(),emptyList(),emptyMap(),emptyList(),listOf(o),6000,16_000)
        assertEquals(60,gap.decision.mph);assertTrue(gap.decision.assumed)
    }
    @Test fun briefGapBeforeLearnedBoundaryDoesNotEraseOwnerCurrent() {
        val b=BoundaryCorrection(old.id,next.id,60,40,Geo.ahead(p,0.0,210.0),Geo.ahead(p,0.0,300.0),0.0,5.0,5.0,.95,0.0,12_000)
        val pipeline=DrivingLimitPipeline()
        assertEquals(60,pipeline.evaluate(fix(270.0,1000),listOf(old,next),emptyList(),emptyMap(),listOf(b),emptyList(),1000,20_000).decision.mph)
        val gap=pipeline.evaluate(fix(280.0,2000),emptyList(),emptyList(),emptyMap(),listOf(b),emptyList(),2000,21_000)
        assertEquals(60,gap.decision.mph);assertTrue(gap.decision.assumed);assertEquals(40,gap.upcoming?.mph)
    }
    @Test fun learnedLocalBypassPreviewsActualNextLimitBeforeFalseIntermediateRoad() {
        val forty=road("next",200.0,260.0,40);val thirty=road("thirty",260.0,1500.0,30)
        val b=BoundaryCorrection(old.id,thirty.id,60,30,Geo.ahead(p,0.0,210.0),Geo.ahead(p,0.0,300.0),
            0.0,5.0,5.0,.95,0.0,12_000,listOf(forty.id),Geo.ahead(p,0.0,240.0))
        val result=DrivingLimitPipeline().evaluate(fix(160.0,1000),listOf(old,forty,thirty),emptyList(),emptyMap(),listOf(b),emptyList(),1000,20_000)
        assertEquals(60,result.decision.mph);assertEquals(30,result.upcoming?.mph)
        val genuine=DrivingLimitPipeline().evaluate(fix(230.0,1000),listOf(old,forty,thirty),emptyList(),emptyMap(),emptyList(),emptyList(),1000,20_000)
        assertEquals(40,genuine.decision.mph)
    }
    @Test fun ownerSecondTapPromotesCurrentImmediatelyOnThisPass() {
        val pipeline=DrivingLimitPipeline()
        for((m,t) in listOf(180.0 to 1000L,225.0 to 2000L,240.0 to 3000L,255.0 to 4000L))
            pipeline.evaluate(fix(m,t),listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t)
        val match=RoadMatch(next,0.0,0.0,.95)
        val first=pipeline.engine.planSelection(fix(260.0,4500),match,40,60,next.id,4500,14_500,emptyList())!!
        pipeline.acceptSavedSelection(first)
        pipeline.evaluate(fix(280.0,5000),listOf(old,next),emptyList(),emptyMap(),emptyList(),listOf(first.observation!!),5000,15_000)
        val second=pipeline.engine.planSelection(fix(300.0,6000),match,40,40,next.id,6000,16_000,listOf(first.observation!!))!!
        pipeline.acceptSavedSelection(second)
        val live=pipeline.evaluate(fix(300.0,6000),listOf(old,next),emptyList(),emptyMap(),listOf(second.boundary!!),emptyList(),6000,16_000).applyTo(DriveState())
        assertEquals(40,live.limitMph);assertFalse(live.limitDecision!!.assumed)
        val future=DrivingLimitPipeline().evaluate(fix(285.0,1000),listOf(old,next),emptyList(),emptyMap(),listOf(second.boundary!!),emptyList(),1000,20_000)
        assertEquals(60,future.decision.mph);assertEquals(40,future.upcoming?.mph)
    }
}
