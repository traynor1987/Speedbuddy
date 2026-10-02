package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class LimitLearningTest {
    private val start = GeoPoint(53.0, -2.0)
    private fun road(id: String, from: Double, to: Double, mph: Int?) = Road(id, "Main Road",
        listOf(Geo.ahead(start, 0.0, from), Geo.ahead(start, 0.0, to)),
        mapOf("highway" to "primary") + (mph?.let { mapOf("maxspeed" to "$it mph") } ?: emptyMap()))
    private val old = road("way/1", 0.0, 200.0, 60)
    private val next = road("way/2", 200.0, 1000.0, null)
    private fun fix(m: Double, at: Long, accuracy: Double = 5.0, bearing: Double = 0.0) =
        Fix(Geo.ahead(start, 0.0, m), accuracy, 10.0, 1.0, bearing, at)
    private fun match(r: Road, confidence: Double = .95, heading: Double = 0.0) = RoadMatch(r, 0.0, heading, confidence)
    private fun decide(e: LimitDecisionEngine, r: Road, m: Double, at: Long, confidence: Double = .95,
        accuracy: Double = 5.0, owner: Int? = null) =
        e.decide(fix(m, at, accuracy), match(r, confidence), SpeedLimits.mph(r.tags), owner, emptyList(), at)

    @Test fun confirmedThirtyAndSixtyAreTemporarilyInheritedOnConnectedUnknownRoad() {
        for (mph in listOf(30, 60)) {
            val e = LimitDecisionEngine()
            decide(e, old.copy(tags = mapOf("maxspeed" to "$mph mph", "highway" to "primary")), 180.0, 1000)
            val result = decide(e, next, 220.0, 2000)
            assertEquals(mph, result.mph)
            assertTrue(result.reason.contains("Assumed"))
            assertTrue(result.reason.contains("way/1"))
        }
    }
    @Test fun assumptionDoesNotRenewItselfAndExpiresByTimeAndDistance() {
        val timed = LimitDecisionEngine(); decide(timed, old, 180.0, 1000)
        decide(timed, next, 220.0, 2000)
        assertNull(decide(timed, next, 230.0, 100_000).mph)
        assertNull(decide(timed, next, 235.0, 101_000).mph)
        val distant = LimitDecisionEngine(); decide(distant, old, 180.0, 1000)
        assertNull(decide(distant, next, 950.0, 2000).mph)
    }
    @Test fun reliableNewDataAndOwnerCorrectionReplaceAssumption() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        decide(e, next, 220.0, 2000)
        assertEquals(40, decide(e, next.copy(tags = mapOf("maxspeed" to "40 mph")), 240.0, 3000).mph)
        val owner = LimitDecisionEngine(); decide(owner, old, 180.0, 1000)
        decide(owner, next, 220.0, 2000)
        val result = decide(owner, next, 240.0, 3000, owner = 40)
        assertEquals(40, result.mph); assertTrue(result.ownerApplied)
    }
    @Test fun lowConfidencePoorGpsTurningAndMotorwayChangeDropAssumption() {
        for (variant in 0..3) {
            val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
            val result = when (variant) {
                0 -> decide(e, next, 220.0, 2000, confidence = .45)
                1 -> decide(e, next, 220.0, 2000, accuracy = 50.0)
                2 -> e.decide(fix(220.0, 2000, bearing = 90.0), match(next), null, null, emptyList(), 2000)
                else -> decide(e, next.copy(tags = mapOf("highway" to "motorway")), 220.0, 2000)
            }
            assertNull(result.mph)
        }
    }
    @Test fun weakIntermediateFortyStaysCandidateThenStableThirtyWins() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        val short = road("way/40", 200.0, 250.0, 40)
        for ((index, m) in listOf(205.0, 215.0, 230.0).withIndex()) {
            val result = decide(e, short, m, 2000L + index * 1000, confidence = .62)
            assertNotEquals(40, result.mph); assertEquals(40, result.upcoming?.mph)
        }
        val thirty = road("way/30", 250.0, 1000.0, 30)
        var result = decide(e, thirty, 260.0, 5000)
        for (i in 1..3) result = decide(e, thirty, 260.0 + i * 12, 5000L + i * 1000)
        assertEquals(30, result.mph)
    }
    @Test fun genuineShortFortyZoneIsConfirmedFromStrongProgression() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        val short = road("way/40", 200.0, 250.0, 40)
        var result = decide(e, short, 210.0, 2000)
        result = decide(e, short, 222.0, 3000)
        result = decide(e, short, 235.0, 4000)
        assertEquals(40, result.mph)
    }
    @Test fun inconsistentHeadingDoesNotPromoteIntermediateLimit() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        val short = road("way/40", 200.0, 250.0, 40)
        for (i in 0..3) {
            val result = e.decide(fix(210.0 + i * 8, 2000L + i * 1000), match(short, .85, 48.0),
                40, null, emptyList(), 2000L + i * 1000)
            assertNotEquals(40, result.mph); assertEquals(40, result.upcoming?.mph)
        }
    }
    @Test fun ownerOverrideWinsOverWeakIntermediateInference() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        assertEquals(30, decide(e, next, 220.0, 2000, confidence = .65, owner = 30).mph)
    }
    @Test fun weakSourceAfterAssumedLimitRemainsUncertainInsteadOfBypassingStabilisation() {
        val e=LimitDecisionEngine();decide(e,old,180.0,1000)
        decide(e,next,220.0,2000)
        val weak=road("way/weak",1000.0,1200.0,40)
        for(i in 0..3) {
            val result=decide(e,weak,1020.0+i*10,3000L+i*1000,confidence=.62)
            assertNotEquals(40,result.mph);assertEquals(40,result.upcoming?.mph)
        }
    }
    @Test fun ownerCanMarkRealBoundaryWhileNewLimitIsStillUpcoming() {
        val e=LimitDecisionEngine();decide(e,old,180.0,1000)
        val thirty=next.copy(tags=mapOf("maxspeed" to "30 mph"))
        val candidate=decide(e,thirty,210.0,2000)
        assertEquals(60,candidate.mph);assertEquals(30,candidate.upcoming?.mph)
        assertTrue(e.canMarkBoundary(2000))
        val correction=e.startsHere(fix(215.0,2500),2500)!!
        assertEquals(60,correction.oldMph);assertEquals(30,correction.newMph)
    }
    @Test fun consecutiveTransitionsMarkCurrentCandidateInsteadOfOlderTransition() {
        val e=LimitDecisionEngine();decide(e,old,180.0,1000)
        val forty=road("way/40",200.0,250.0,40)
        for(i in 0..2) decide(e,forty,210.0+i*12,2000L+i*1000)
        val thirty=road("way/30",250.0,1000.0,30)
        assertEquals(40,decide(e,thirty,265.0,5000).mph)
        assertTrue(e.canMarkBoundary(5000))
        val b=e.startsHere(fix(270.0,5500),5500)!!
        assertEquals("way/40",b.fromId);assertEquals("way/30",b.toId)
        assertEquals(40,b.oldMph);assertEquals(30,b.newMph)
    }
    @Test fun unknownOwnerSelectionDoesNotFallBackToDownloadedOrAssumedLimit() {
        val e = LimitDecisionEngine(); decide(e, old, 180.0, 1000)
        val result = decide(e, next.copy(tags=mapOf("maxspeed" to "40 mph")), 220.0, 2000, owner=0)
        assertNull(result.mph);assertTrue(result.ownerApplied)
    }
    @Test fun quickBoundaryWorksWithoutReportingTooEarlyFirst() {
        val e = LimitDecisionEngine();decide(e, old,180.0,1000)
        val thirty = next.copy(tags=mapOf("maxspeed" to "30 mph"))
        for (i in 0..2) decide(e,thirty,220.0+i*10,2000L+i*1000)
        val b = e.startsHere(fix(300.0,6000),6000)!!
        assertEquals("way/1",b.fromId);assertEquals("way/2",b.toId)
        assertEquals(60,b.oldMph);assertEquals(30,b.newMph)
        val replay=LimitDecisionEngine()
        replay.decide(fix(180.0,1000),match(old),60,null,listOf(b),1000)
        val before=replay.decide(fix(250.0,2000),match(thirty),30,null,listOf(b),2000)
        assertEquals(60,before.mph);assertEquals(30,before.upcoming?.mph)
        assertEquals(30,replay.decide(fix(320.0,3000),match(thirty),30,null,listOf(b),3000).mph)
        val reverse=LimitDecisionEngine().decide(fix(250.0,1000,bearing=180.0),match(thirty),30,null,listOf(b),1000)
        assertEquals(30,reverse.mph);assertFalse(reverse.boundaryApplied)
    }
    @Test fun poorGpsCannotLearnDirectBoundary() {
        val e=LimitDecisionEngine();decide(e,old,180.0,1000)
        val thirty=next.copy(tags=mapOf("maxspeed" to "30 mph"))
        for(i in 0..2) decide(e,thirty,220.0+i*10,2000L+i*1000)
        assertNull(e.startsHere(fix(300.0,6000,accuracy=70.0),6000))
    }
    @Test fun savedOwnerBoundaryOutranksRefreshedOrMissingSourceTag() {
        val b=BoundaryCorrection(old.id,next.id,60,30,Geo.ahead(start,0.0,210.0),Geo.ahead(start,0.0,300.0),0.0,5.0,5.0,.95,0.0,1234)
        for(source in listOf<Int?>(40,null)) {
            val e=LimitDecisionEngine()
            e.decide(fix(180.0,1000),match(old),60,null,listOf(b),1000)
            assertEquals(60,e.decide(fix(250.0,2000),match(next),source,null,listOf(b),2000).mph)
            val after=e.decide(fix(320.0,3000),match(next),source,null,listOf(b),3000)
            assertEquals(30,after.mph);assertTrue(after.boundaryApplied)
            assertEquals(40,e.decide(fix(330.0,4000),match(next),source,40,listOf(b),4000).mph)
        }
    }
    @Test fun quickCorrectionTargetsMatchedRoadAndRejectsStalePickerOrPoorGps() {
        val f=fix(220.0,2000)
        val row=QuickLimitCorrection.capture(f,match(next),null,40,"way/2",2000)!!
        assertEquals("way/2",row.road);assertEquals(40,row.mph);assertNull(row.sourceMph)
        assertEquals(f.point,row.point);assertEquals(0.0,row.bearing,0.0);assertTrue(row.recordedAt>0)
        assertNull(QuickLimitCorrection.capture(f,match(next),null,40,"way/1",2000))
        assertNull(QuickLimitCorrection.capture(f.copy(accuracyM=70.0),match(next),null,40,"way/2",2000))
        assertNull(QuickLimitCorrection.capture(f,match(next),null,40,"way/2",8000))
        assertNull(QuickLimitCorrection.capture(f,match(next,.4),null,40,"way/2",2000))
    }
    @Test fun nationalSelectionDoesNotGuessDualCarriagewayFromLaneCount() {
        assertNull(OwnerLimit.nationalMph(next.copy(tags=mapOf("lanes" to "4"))))
        assertEquals(70,OwnerLimit.nationalMph(next.copy(tags=mapOf("highway" to "motorway"))))
        assertEquals(60,OwnerLimit.nationalMph(next.copy(tags=mapOf("maxspeed:type" to "GB:nsl_single"))))
    }
}
