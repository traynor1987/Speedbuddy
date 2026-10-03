package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class NaturalBoundaryLearningTest {
    private val p=GeoPoint(53.0,-2.0)
    private fun road(id: String,a: Double,b: Double,mph: Int)=Road(id,"Main",
        listOf(Geo.ahead(p,0.0,a),Geo.ahead(p,0.0,b)),mapOf("highway" to "primary","maxspeed" to "$mph mph"))
    private val old=road("old",0.0,200.0,60)
    private val next=road("next",200.0,1200.0,40)
    private fun fix(m: Double,t: Long,bearing: Double=0.0)=Fix(Geo.ahead(p,0.0,m),5.0,10.0,1.0,bearing,t)
    private fun match(r: Road)=RoadMatch(r,0.0,0.0,.95)
    private fun seeded(): LimitDecisionEngine {
        val e=LimitDecisionEngine()
        e.decide(fix(180.0,1000),match(old),60,null,emptyList(),1000)
        for(i in 0..2) e.decide(fix(210.0+i*12,2000L+i*1000),match(next),40,null,emptyList(),2000L+i*1000)
        return e
    }
    private fun first(e: LimitDecisionEngine)=e.planSelection(fix(240.0,4500),match(next),40,60,next.id,4500,10_000,emptyList())!!
    @Test fun ordinaryOldLimitTapProducesLocalObservationRatherThanRoadOverride() {
        val plan=first(seeded())
        assertNull(plan.override);assertNull(plan.boundary)
        assertEquals(60,plan.observation!!.oldMph)
        assertEquals(Geo.ahead(p,0.0,240.0),plan.observation!!.stillPoint)
    }
    @Test fun ordinarySecondTapPairsAfterRestartAndReplaysCorrectedBoundary() {
        val observation=first(seeded()).observation!!
        val restarted=LimitDecisionEngine()
        restarted.decide(fix(285.0,5000),match(next),40,null,emptyList(),5000,listOf(observation),11_000)
        val plan=restarted.planSelection(fix(300.0,6000),match(next),40,40,next.id,6000,12_000,listOf(observation))!!
        assertNull(plan.override);assertNotNull(plan.boundary)
        val b=plan.boundary!!
        assertEquals(60,b.oldMph);assertEquals(40,b.newMph)
        val replay=LimitDecisionEngine()
        val before=replay.decide(fix(260.0,1000),match(next),40,null,listOf(b),1000)
        assertEquals(60,before.mph);assertEquals(40,before.upcoming?.mph);assertTrue(before.boundaryApplied)
        assertEquals(40,replay.decide(fix(320.0,2000),match(next),40,null,listOf(b),2000).mph)
        val reverse=LimitDecisionEngine().decide(fix(260.0,1000,180.0),match(next),40,null,listOf(b),1000)
        assertEquals(40,reverse.mph);assertTrue(reverse.boundaryApplied)
        assertEquals(60,reverse.upcoming?.mph)
    }
    @Test fun linkedOrOneWayBoundaryIsNotMirrored() {
        val link=Road("link","Main",next.points,mapOf("highway" to "primary_link","maxspeed" to "40 mph","oneway" to "yes"))
        val b=BoundaryCorrection(old.id,link.id,60,40,Geo.ahead(p,0.0,210.0),Geo.ahead(p,0.0,300.0),
            0.0,5.0,5.0,.95,0.0,12_000)
        val reverse=LimitDecisionEngine().decide(fix(260.0,1000,180.0),match(link),40,null,listOf(b),1000)
        assertEquals(40,reverse.mph);assertFalse(reverse.boundaryApplied)
    }
    @Test fun falseIntermediateIsOnlyBypassedInsideObservedDirectedBoundaryScope() {
        val forty=road("next",200.0,260.0,40)
        val thirty=road("thirty",260.0,1200.0,30)
        val e=LimitDecisionEngine();e.decide(fix(180.0,1000),match(old),60,null,emptyList(),1000)
        for(i in 0..2) e.decide(fix(210.0+i*12,2000L+i*1000),match(forty),40,null,emptyList(),2000L+i*1000)
        val o=e.planSelection(fix(240.0,4500),match(forty),40,60,forty.id,4500,10_000,emptyList())!!.observation!!
        e.decide(fix(280.0,5000),match(thirty),30,null,emptyList(),5000,listOf(o),11_000)
        val b=e.planSelection(fix(300.0,6000),match(thirty),30,30,thirty.id,6000,12_000,listOf(o))!!.boundary!!
        assertEquals(60,b.oldMph);assertEquals(30,b.newMph);assertTrue(forty.id in b.viaIds)
        assertEquals(60,LimitDecisionEngine().decide(fix(230.0,1000),match(forty),40,null,listOf(b),1000).mph)
        assertEquals(60,LimitDecisionEngine().decide(fix(280.0,1000),match(thirty),30,null,listOf(b),1000).mph)
        assertEquals(30,LimitDecisionEngine().decide(fix(320.0,1000),match(thirty),30,null,listOf(b),1000).mph)
        assertEquals(40,LimitDecisionEngine().decide(fix(230.0,1000,180.0),match(forty),40,null,listOf(b),1000).mph)
        assertEquals(40,LimitDecisionEngine().decide(fix(230.0,1000),match(forty.copy(id="genuine")),40,null,listOf(b),1000).mph)
    }
    @Test fun invalidOrExpiredObservationsCannotPair() {
        val o=first(seeded()).observation!!
        val e=LimitDecisionEngine()
        assertNull(e.planSelection(fix(300.0,6000,180.0),match(next),40,40,next.id,6000,12_000,listOf(o))!!.boundary)
        assertNull(e.planSelection(fix(300.0,6000),match(next),40,40,next.id,6000,200_000,listOf(o))!!.boundary)
        assertNull(e.planSelection(fix(300.0,6000).copy(accuracyM=70.0),match(next),40,40,next.id,6000,12_000,listOf(o)))
        assertNull(e.planSelection(fix(300.0,6000),match(next),40,40,"stale",6000,12_000,listOf(o)))
    }
    @Test fun earlierCandidateWholeRoadCorrectionDoesNotBlockNaturalBoundaryLearning() {
        val e=LimitDecisionEngine()
        e.decide(fix(180.0,1000),match(old),60,null,emptyList(),1000)
        e.decide(fix(225.0,2000),match(next),40,40,emptyList(),2000)
        val first=e.planSelection(fix(240.0,2500),match(next),40,60,next.id,2500,10_000,emptyList())!!
        assertNull(first.override);assertNotNull(first.observation)
        val held=e.decide(fix(260.0,3000),match(next),40,40,emptyList(),3000,listOf(first.observation!!),11_000)
        assertEquals(60,held.mph);assertEquals(40,held.upcoming?.mph)
    }
    @Test fun savedBoundaryWaitsBeyondBothCurrentAndObservedGpsUncertainty() {
        val b=BoundaryCorrection(old.id,next.id,60,40,Geo.ahead(p,0.0,210.0),Geo.ahead(p,0.0,300.0),
            0.0,5.0,20.0,.95,0.0,12_000)
        val e=LimitDecisionEngine()
        val near=e.decide(fix(307.0,1000),match(next),40,null,listOf(b),1000)
        assertEquals(60,near.mph);assertEquals(40,near.upcoming?.mph)
        assertEquals(40,e.decide(fix(330.0,2000),match(next),40,null,listOf(b),2000).mph)
    }
}
