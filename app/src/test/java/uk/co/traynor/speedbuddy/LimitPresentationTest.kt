package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class LimitPresentationTest {
    private val p=GeoPoint(53.55,-2.8)
    private val j=Geo.ahead(p,0.0,100.0)
    private val old=Road("way/1","Approach",listOf(p,j),mapOf("highway" to "residential","maxspeed" to "20 mph"))
    private fun outgoing(b: Double)=Road("way/2","Exit",listOf(j,Geo.ahead(j,b,200.0)),mapOf("highway" to "residential","maxspeed" to "30 mph"))
    private fun fix(point: GeoPoint,time: Long,b: Double?=0.0,speed: Double=8.0)=Fix(point,5.0,speed,1.0,b,time)
    private fun match(r: Road)=RoadMatch(r,0.0,0.0,.95,0.0)
    private fun known(mph: Int)=LimitDecision(mph,reason="Confirmed")
    @Test fun freshConfirmationClearsWarningWithoutPresentationTimer() {
        val display=LimitPresentation()
        val a=fix(Geo.ahead(p,0.0,30.0),1000)
        assertFalse(display.resolve(a,match(old),known(20),listOf(old),true).assumed)
        assertTrue(display.resolve(a.copy(elapsedMs=2000),match(old),known(20).copy(assumed=true),listOf(old),true).assumed)
        assertFalse(display.resolve(a.copy(elapsedMs=3000),match(old),known(20),listOf(old),true).assumed)
        assertTrue(display.resolve(a.copy(elapsedMs=4000),match(old),known(20).copy(assumed=true),listOf(old),true).assumed)
        assertFalse(display.resolve(a.copy(elapsedMs=5000),match(old),known(20),listOf(old),true).assumed)
        assertFalse(display.resolve(a.copy(elapsedMs=6000),match(old),known(20),listOf(old),true).assumed)
    }
    @Test fun servicePendingFramesCannotDowngradeCompletedConfirmation() {
        val pipeline=DrivingLimitPipeline();val a=fix(Geo.ahead(p,0.0,30.0),1000)
        fun evaluate(f: Fix, roads: List<Road> = listOf(old))=pipeline.evaluate(f,roads,emptyList(),emptyMap(),emptyList(),emptyList(),f.elapsedMs,f.elapsedMs)
        assertFalse(evaluate(a).decision.assumed)
        for(t in listOf(2000L,3000L,4000L)) {
            val prior=evaluate(a.copy(elapsedMs=t-1000)).applyTo(DriveState(active=true,speedMph=20.0))
            DriveBus.set(prior);DriveBus.publishLocationSpeed(20.0,a.copy(elapsedMs=t))
            assertTrue(DriveBus.state.value.limitDecision!!.assumed)
            val result=evaluate(a.copy(elapsedMs=t))
            assertEquals(20,result.decision.mph);assertFalse(result.decision.assumed)
            assertFalse(result.presentation.assumed)
        }
        // UI caution cannot extend the real engine's expired numeric evidence.
        assertNull(evaluate(a.copy(elapsedMs=7001),emptyList()).decision.mph)
    }
    @Test fun unknownIsNeverFilledByPresentationAndNewVerifiedLimitIsImmediate() {
        val d=LimitPresentation();val f=fix(p,1000)
        d.resolve(f,match(old),known(20).copy(assumed=true),listOf(old),true)
        assertEquals(30,d.resolve(f.copy(elapsedMs=1500),match(old),known(30),listOf(old),true).mph)
        assertFalse(d.resolve(f.copy(elapsedMs=1600),match(old),known(30),listOf(old),true).assumed)
        assertNull(d.resolve(f.copy(elapsedMs=5000),null,LimitDecision(null,reason="Expired"),listOf(old),true).mph)
    }
    @Test fun boundaryCandidatePreservesEngineEvidenceWithoutDisplayingPreviousRoadNumber() {
        val pipeline=DrivingLimitPipeline()
        val approach=old.copy(points=listOf(p,Geo.ahead(p,0.0,200.0)),tags=mapOf("maxspeed" to "60 mph"))
        val exit=outgoing(0.0).copy(points=listOf(Geo.ahead(p,0.0,200.0),Geo.ahead(p,0.0,1500.0)),tags=mapOf("maxspeed" to "40 mph"))
        fun evaluate(m: Double,t: Long,roads: List<Road>)=pipeline.evaluate(fix(Geo.ahead(p,0.0,m),t),roads,emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t)
        assertEquals(60,evaluate(180.0,1000,listOf(approach,exit)).presentation.mph)
        assertEquals(60,evaluate(190.0,2000,emptyList()).presentation.mph)
        val candidate=evaluate(225.0,3000,listOf(approach,exit))
        assertEquals(60,candidate.decision.mph);assertEquals(40,candidate.decision.upcoming!!.mph)
        assertNull(candidate.presentation.mph);assertTrue(candidate.presentation.changing)
        evaluate(240.0,4000,listOf(approach,exit))
        assertEquals(40,evaluate(260.0,5000,listOf(approach,exit)).presentation.mph)
    }
    @Test fun connectedLeftRightAndStraightTransitionsExpireWithoutRestarting() {
        for(b in listOf(270.0,90.0,0.0)) {
            val d=LimitPresentation();val next=outgoing(b);val roads=listOf(old,next)
            d.resolve(fix(Geo.ahead(j,180.0,25.0),1000),match(old),known(20),roads,true)
            val after=fix(Geo.ahead(j,b,14.0),2000,b)
            val changing=d.resolve(after,null,LimitDecision(null,reason="Resolving"),roads,true)
            assertTrue("bearing $b",changing.changing);assertNull(changing.mph)
            assertTrue(d.resolve(after.copy(elapsedMs=6000),null,LimitDecision(null,reason="Resolving"),roads,true).changing)
            assertFalse(d.resolve(after.copy(elapsedMs=7000),null,LimitDecision(null,reason="Resolving"),roads,true).changing)
            assertFalse(d.resolve(after.copy(elapsedMs=8000),null,LimitDecision(null,reason="Resolving"),roads,true).changing)
        }
    }
    @Test fun stationaryUncertainHeadingParallelAndIncompleteContextStayUnknown() {
        for(mode in 0..3) {
            val d=LimitPresentation();val next=if(mode==2) outgoing(0.0).copy(points=listOf(Geo.ahead(j,90.0,25.0),Geo.ahead(Geo.ahead(j,90.0,25.0),0.0,200.0))) else outgoing(90.0)
            val roads=listOf(old,next)
            d.resolve(fix(Geo.ahead(j,180.0,25.0),1000),match(old),known(20),roads,true)
            val result=d.resolve(fix(Geo.ahead(j,90.0,14.0),2000,if(mode==1)null else 90.0,if(mode==0)0.0 else 8.0),null,LimitDecision(null,reason="Resolving"),roads,mode!=3)
            assertFalse("mode $mode",result.changing);assertNull(result.mph)
        }
    }
    @Test fun transitionExpiresWithoutNewGpsAndFreshPublicationClearsPendingAnchor() {
        val f=fix(p,1000)
        DriveBus.set(DriveState(active=true,fix=f,limitDecision=known(20).copy(assumed=true),limitPresentation=LimitDecision(null,reason="Resolving",changing=true,changingUntilElapsedMs=6000)))
        DriveBus.expirePending(4000);assertTrue(DriveBus.state.value.limitPresentation!!.changing)
        DriveBus.expireTransition(5999);assertTrue(DriveBus.state.value.limitPresentation!!.changing)
        DriveBus.expireTransition(6000);assertFalse(DriveBus.state.value.limitPresentation!!.changing)
        val fresh=DriveLimitResult(f,match(old),20,known(20),null).applyTo(DriveState(pendingLimitAnchor=f,pendingLimitDistanceM=29.0))
        assertNull(fresh.pendingLimitAnchor);assertEquals(0.0,fresh.pendingLimitDistanceM,0.0)
        DriveBus.set(fresh.copy(limitPresentation=known(20).copy(assumed=true)))
        DriveBus.expirePending(3001);assertNull(DriveBus.state.value.limitMph)
        DriveBus.set(DriveState())
    }
    @Test fun verifiedLimitEndsChangingImmediatelyAndStaleFramesCannotResetHistory() {
        val d=LimitPresentation();val next=outgoing(90.0);val roads=listOf(old,next)
        d.resolve(fix(Geo.ahead(j,180.0,25.0),1000),match(old),known(20),roads,true)
        val after=fix(Geo.ahead(j,90.0,14.0),2000,90.0)
        assertTrue(d.resolve(after,null,LimitDecision(null,reason="Resolving"),roads,true).changing)
        assertEquals(30,d.resolve(after.copy(elapsedMs=2500),match(next),known(30),roads,true).mph)
        assertNull(d.resolve(after.copy(elapsedMs=1500),null,LimitDecision(null,reason="Old"),roads,true).mph)
        assertEquals(30,d.resolve(after.copy(elapsedMs=3000),match(next),known(30),roads,true).mph)
    }
}
