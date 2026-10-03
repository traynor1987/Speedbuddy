package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class LimitDecisionTest {
    private val old = Road("way/1", "High Street", listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)), mapOf("maxspeed" to "30 mph"))
    private val next = old.copy(id = "way/2", points = listOf(GeoPoint(53.003, -2.0), GeoPoint(53.02, -2.0)), tags = mapOf("maxspeed" to "20 mph"))
    private fun fix(lat: Double, time: Long, bearing: Double = 0.0) = Fix(GeoPoint(lat, -2.0), 5.0, 10.0, 1.0, bearing, time)
    private fun match(road: Road) = RoadMatch(road, 0.0, 0.0, .95)
    @Test fun previewAndJitterDoNotPromoteNewLimit() {
        val engine = LimitDecisionEngine()
        assertEquals(30, engine.decide(fix(53.002,1000), match(old), 30, null, emptyList(), 1000).mph)
        val preview = UpcomingLimitDetector().detect(fix(53.002,1000), match(old), 30, listOf(old,next))
        assertEquals(20, preview?.mph)
        val jitter = engine.decide(fix(53.0029,2000), match(next), 20, null, emptyList(), 2000)
        assertEquals(30, jitter.mph); assertEquals(20, jitter.upcoming?.mph)
        assertEquals(30, engine.decide(fix(53.0029,3000), match(old), 30, null, emptyList(), 3000).mph)
    }
    private fun transitioned(engine: LimitDecisionEngine) {
        engine.decide(fix(53.002,1000), match(old),30,null,emptyList(),1000)
        for (time in listOf(2000L,3000L,4000L)) engine.decide(fix(53.0035,time),match(next),20,null,emptyList(),time)
    }
    @Test fun tooEarlyChangedNowRecordsEvidenceAndReplaysBoundary() {
        val engine = LimitDecisionEngine(); transitioned(engine)
        assertTrue(engine.canReport(4000))
        assertTrue(engine.tooEarly(fix(53.0036,4500),4500))
        assertEquals(30, engine.decide(fix(53.004,5000),match(next),20,null,emptyList(),5000).mph)
        val boundary = engine.changedNow(fix(53.006,6000),6000)!!
        assertEquals(old.id,boundary.fromId); assertEquals(next.id,boundary.toId)
        assertEquals(30,boundary.oldMph); assertEquals(20,boundary.newMph)
        assertEquals(5.0,boundary.observedAccuracy,0.0)
        assertEquals(0.0,boundary.bearing,0.0)
        assertEquals(.95,boundary.confidence,0.0)
        val replay = LimitDecisionEngine()
        replay.decide(fix(53.002,1000),match(old),30,null,listOf(boundary),1000)
        val before = replay.decide(fix(53.004,2000),match(next),20,null,listOf(boundary),2000)
        assertEquals(30,before.mph); assertTrue(before.boundaryApplied); assertEquals(20,before.upcoming?.mph)
        assertEquals(20,replay.decide(fix(53.0062,3000),match(next),20,null,listOf(boundary),3000).mph)
    }
    @Test fun oppositeDirectionAndOtherRoadAreIsolated() {
        val engine = LimitDecisionEngine(); transitioned(engine); engine.tooEarly(fix(53.0036,4500),4500)
        val boundary = engine.changedNow(fix(53.006,6000),6000)!!
        val opposite = LimitDecisionEngine()
        assertEquals(20,opposite.decide(fix(53.004,1000,180.0),match(next),20,null,listOf(boundary),1000).mph)
        assertFalse(opposite.decide(fix(53.004,2000,180.0),match(next),20,null,listOf(boundary),2000).boundaryApplied)
        val unrelated = next.copy(id="way/99")
        assertFalse(LimitDecisionEngine().decide(fix(53.004,1000),match(unrelated),20,null,listOf(boundary),1000).boundaryApplied)
    }
    @Test fun ownerOverrideWinsAndUnknownNeverInventsLimit() {
        val engine = LimitDecisionEngine()
        assertEquals(40, engine.decide(fix(53.002,1000),match(old),30,40,emptyList(),1000).mph)
        assertTrue(engine.decide(fix(53.002,2000),match(old),30,40,emptyList(),2000).ownerApplied)
        assertNull(LimitDecisionEngine().decide(fix(53.002,1000),match(old.copy(tags=emptyMap())),null,null,emptyList(),1000).mph)
    }
    @Test fun sameLimitContinuationNeverCreatesTransition() {
        val engine = LimitDecisionEngine()
        engine.decide(fix(53.002,1000),match(old),30,null,emptyList(),1000)
        val result = engine.decide(fix(53.0031,2000),match(next),30,null,emptyList(),2000)
        assertEquals(30,result.mph); assertNull(result.upcoming); assertFalse(engine.canReport(2000))
    }
    @Test fun crossedBoundaryDoesNotRevertOnCurvingToRoad() {
        val engine=LimitDecisionEngine();transitioned(engine);engine.tooEarly(fix(53.0036,4500),4500)
        val b=engine.changedNow(fix(53.006,6000),6000)!!
        val curved=next.copy(points=listOf(next.points.first(),GeoPoint(53.006,-2.0),GeoPoint(53.01,-1.998)))
        val replay=LimitDecisionEngine()
        replay.decide(fix(53.002,1000),match(old),30,null,listOf(b),1000)
        assertEquals(20,replay.decide(fix(53.0062,2000),match(curved),20,null,listOf(b),2000).mph)
        val later=fix(53.009,3000,20.0).copy(point=GeoPoint(53.009,-1.9985))
        assertEquals(20,replay.decide(later,match(curved),20,null,listOf(b),3000).mph)
        assertEquals(20,LimitDecisionEngine().decide(later,match(curved),20,null,listOf(b),1000).mph)
    }
    @Test fun turningBackOnSameWayRearmsLearnedBoundary() {
        val engine=LimitDecisionEngine();transitioned(engine);engine.tooEarly(fix(53.0036,4500),4500)
        val b=engine.changedNow(fix(53.006,6000),6000)!!
        val replay=LimitDecisionEngine()
        assertEquals(20,replay.decide(fix(53.0062,1000),match(next),20,null,listOf(b),1000).mph)
        replay.decide(fix(53.005,2000,180.0),match(next),20,null,listOf(b),2000)
        assertEquals(30,replay.decide(fix(53.005,3000),match(next),20,null,listOf(b),3000).mph)
    }
    @Test fun pendingFeedbackNeverHoldsAnInaccurateOrUnmatchedLimit() {
        val engine=LimitDecisionEngine();transitioned(engine);engine.tooEarly(fix(53.0036,4500),4500)
        assertNull(engine.decide(fix(53.004,5000).copy(accuracyM=70.0),match(next),20,null,emptyList(),5000).mph)
        assertNull(engine.decide(fix(53.004,6000).copy(point=GeoPoint(53.004,-1.999)),null,null,null,emptyList(),6000).mph)
    }
    @Test fun inaccurateLateOrWrongRoadFeedbackIsRejected() {
        val engine = LimitDecisionEngine(); transitioned(engine)
        assertFalse(engine.tooEarly(fix(53.0035,5000).copy(accuracyM=70.0),5000))
        assertFalse(engine.tooEarly(fix(53.0035,50_000),50_000))
        val e = LimitDecisionEngine(); transitioned(e); assertTrue(e.tooEarly(fix(53.0036,4500),4500))
        e.decide(fix(53.005,5000),match(next.copy(id="other")),20,null,emptyList(),5000)
        assertNull(e.changedNow(fix(53.006,6000),6000))
    }
}
