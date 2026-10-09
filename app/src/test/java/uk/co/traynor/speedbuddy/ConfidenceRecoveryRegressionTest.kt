package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class ConfidenceRecoveryRegressionTest {
    private val p=GeoPoint(53.55,-2.8)
    private val j=Geo.ahead(p,0.0,100.0)
    private fun road(id: String,mph: Int,start: GeoPoint=p,end: GeoPoint=Geo.ahead(p,0.0,400.0))=
        Road(id,id,listOf(start,end),mapOf("highway" to "residential","maxspeed" to "$mph mph"))
    private fun fix(point: GeoPoint,t: Long,bearing: Double?=0.0)=Fix(point,5.0,8.0,1.0,bearing,t)
    private fun regional(r: Road)=RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,
        RoadMatch(r,0.0,0.0,.95,0.0),candidates=listOf(r),coverage=true)
    private fun evaluate(pipeline: DrivingLimitPipeline,f: Fix,r: Road,regional: RegionalPackMatcher.Result?=regional(r),live: LiveRoadState?=null)=
        pipeline.evaluate(f,listOf(r),emptyList(),emptyMap(),emptyList(),emptyList(),f.elapsedMs,f.elapsedMs,regional,live)

    private fun publishPending(r: Road) {
        val prior=fix(Geo.ahead(p,0.0,50.0),0)
        DriveBus.set(DriveState(active=true,fix=prior,road=regional(r).match,limitMph=30,
            limitDecision=LimitDecision(30,reason="Confirmed"),roadDecisionElapsedMs=0))
        DriveBus.publishLocationSpeed(29.0,prior.copy(elapsedMs=1000))
        assertTrue(DriveBus.state.value.limitDecision!!.assumed)
    }
    @Test fun everyFreshRegionalConfirmationClearsProcessingAndGenuineAssumption() {
        val pipeline=DrivingLimitPipeline();val r=road("way/1",30)
        for(t in listOf(1000L,2000L,3000L)) {
            publishPending(r)
            val result=evaluate(pipeline,fix(Geo.ahead(p,0.0,50.0),t),r)
            assertEquals(30,result.presentation.mph);assertFalse(result.presentation.assumed)
        }
        val display=LimitPresentation();val f=fix(p,1000)
        display.resolve(f,regional(r).match,LimitDecision(30,reason="Continuity",assumed=true),listOf(r),true)
        assertFalse(display.resolve(f.copy(elapsedMs=1100),regional(r).match,LimitDecision(30,reason="Confirmed"),listOf(r),true).assumed)
    }
    @Test fun sameLimitJunctionUpdatesIdentityWithoutChangingPresentationOrVoice() {
        val pipeline=DrivingLimitPipeline();val a=road("way/1",30,p,j);val b=road("way/2",30,j,Geo.ahead(j,90.0,200.0))
        val voice=DeferredLimitVoice()
        val first=evaluate(pipeline,fix(Geo.ahead(j,180.0,20.0),1000),a)
        assertNull(voice.update(first.presentation.mph,false,true))
        val second=evaluate(pipeline,fix(Geo.ahead(j,90.0,25.0),2000,90.0),b,
            regional(b).copy(match=RoadMatch(b,0.0,0.0,.95,90.0),candidates=listOf(a,b)))
        assertEquals(b.id,second.road!!.road.id);assertEquals(30,second.presentation.mph)
        assertFalse(second.presentation.changing);assertFalse(second.presentation.assumed)
        assertNull(voice.update(second.presentation.mph,false,true))
    }
    @Test fun reliablyCrossedRegionalDifferentLimitAppearsWithoutExtraTimer() {
        val pipeline=DrivingLimitPipeline();val a=road("way/1",20,p,j);val b=road("way/2",30,j,Geo.ahead(j,0.0,200.0))
        evaluate(pipeline,fix(Geo.ahead(j,180.0,20.0),1000),a)
        val result=evaluate(pipeline,fix(Geo.ahead(j,0.0,25.0),2000),b,
            regional(b).copy(candidates=listOf(a,b)))
        assertEquals(30,result.presentation.mph);assertFalse(result.presentation.changing)
    }
    @Test fun sameNumericUpcomingIsNotAnUnresolvedTransition() {
        val display=LimitPresentation();val a=road("way/1",30,p,j);val b=road("way/2",30,j,Geo.ahead(j,90.0,200.0))
        display.resolve(fix(Geo.ahead(j,180.0,20.0),1000),regional(a).match,LimitDecision(30,reason="Confirmed"),listOf(a,b),true)
        val result=display.resolve(fix(Geo.ahead(j,90.0,25.0),2000,90.0),regional(b).match,
            LimitDecision(30,upcoming=UpcomingLimit(30,10.0,false,roadId=b.id),reason="Confirmed"),listOf(a,b),true)
        assertEquals(30,result.mph);assertFalse(result.changing)
    }
    @Test fun confirmedLegacyAndCompatibleLiveNeverReceiveArtificialAssumedLabel() {
        for(live in listOf<LiveRoadState?>(null,LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,30,false,"way/1"))) {
            val pipeline=DrivingLimitPipeline();val r=road("way/1",30)
            publishPending(r)
            val result=evaluate(pipeline,fix(Geo.ahead(p,0.0,50.0),2000),r,null,live)
            assertEquals(30,result.presentation.mph);assertFalse(result.presentation.assumed)
        }
    }
    @Test fun stationaryUndirectedRegionalLimitIsConfirmedOnEveryNewFix() {
        val pipeline=DrivingLimitPipeline();val r=road("way/1",30)
        for(t in 1000L..4000L step 1000) {
            publishPending(r)
            val result=evaluate(pipeline,fix(Geo.ahead(p,0.0,50.0),t,null).copy(speedMps=0.0),r,
                regional(r).copy(match=RoadMatch(r,0.0,null,.95,0.0)))
            assertEquals(30,result.presentation.mph);assertFalse(result.presentation.assumed)
        }
    }
    @Test fun genuineRegionalGapRemainsAssumedAndCannotBeFilledByLegacyOrLive() {
        val pipeline=DrivingLimitPipeline();val r=road("way/1",30)
        evaluate(pipeline,fix(Geo.ahead(p,0.0,50.0),1000),r)
        val gap=RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,candidates=listOf(r),coverage=true)
        val uncertain=evaluate(pipeline,fix(Geo.ahead(p,0.0,55.0),2000),r,gap,
            LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,30,false,r.id))
        assertTrue(uncertain.presentation.assumed);assertEquals(30,uncertain.presentation.mph)
        val expired=evaluate(pipeline,fix(Geo.ahead(p,0.0,55.0),3001),r,gap)
        assertNull(expired.presentation.mph)
        val recovered=evaluate(pipeline,fix(Geo.ahead(p,0.0,55.0),4000),r)
        assertFalse(recovered.presentation.assumed);assertEquals(30,recovered.presentation.mph)
    }
    @Test fun unresolvedTurnKeepsBoundedChangingThenUnknown() {
        val display=LimitPresentation();val a=road("way/1",30,p,j);val b=road("way/2",30,j,Geo.ahead(j,90.0,200.0)).copy(tags=emptyMap())
        display.resolve(fix(Geo.ahead(j,180.0,20.0),1000),regional(a).match,LimitDecision(30,reason="Confirmed"),listOf(a,b),true)
        val f=fix(Geo.ahead(j,90.0,25.0),2000,90.0)
        val changing=display.resolve(f,null,LimitDecision(null,reason="Unknown"),listOf(a,b),true)
        assertTrue(changing.changing);assertNull(changing.mph)
        val expired=display.resolve(f.copy(elapsedMs=7000),null,LimitDecision(null,reason="Unknown"),listOf(a,b),true)
        assertFalse(expired.changing);assertNull(expired.mph)
    }
    @Test fun parallelRoadAmbiguityDoesNotBecomeConfirmedThroughEqualNumbers() {
        val a=road("way/1",30);val b=road("way/2",30,Geo.ahead(p,90.0,6.0),Geo.ahead(Geo.ahead(p,90.0,6.0),0.0,400.0))
        val f=fix(Geo.ahead(Geo.ahead(p,0.0,50.0),90.0,3.0),1000)
        assertNull(RoadMatcher().match(f,listOf(a,b)))
        val pipeline=DrivingLimitPipeline()
        val result=evaluate(pipeline,f,a,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,candidates=listOf(a,b)))
        assertNull(result.presentation.mph)
    }
    @Test fun ownerPriorityDirectionalEvidenceAndStaleFramesRemainProtected() {
        val pipeline=DrivingLimitPipeline();val r=road("way/1",30).copy(tags=mapOf("maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph"))
        val f=fix(Geo.ahead(p,0.0,50.0),2000)
        val forward=evaluate(pipeline,f,r);assertEquals(30,forward.presentation.mph)
        val reverse=evaluate(pipeline,f.copy(elapsedMs=3000,bearing=180.0),r,
            regional(r).copy(match=RoadMatch(r,0.0,0.0,.95,0.0)))
        assertEquals(50,reverse.presentation.mph)
        val owner=pipeline.evaluate(f.copy(elapsedMs=4000,bearing=180.0),listOf(r),
            listOf(RoadDb.Override(r.id,180.0,20)),emptyMap(),emptyList(),emptyList(),4000,4000,regional(r))
        assertEquals(20,owner.presentation.mph);assertTrue(owner.decision.ownerApplied)
        assertNull(evaluate(pipeline,f,r).presentation.mph)
        val state=reverse.applyTo(DriveState())
        assertEquals(state,evaluate(pipeline,f,r).applyTo(state))
    }
    @Test fun sameLimitJunctionStillAllowsNewCameraEncounterAndNoRepeatedLimitSpeech() {
        val a=road("way/1",30,p,j);val b=road("way/2",30,j,Geo.ahead(j,0.0,300.0))
        val camera=Camera("user/1",Geo.ahead(j,0.0,180.0),CameraType.SPEED,CameraSource.USER,0.0,30)
        val detector=CameraApproachDetector();val voice=DeferredLimitVoice()
        assertNull(voice.update(30,false,true))
        val f=fix(Geo.ahead(j,0.0,25.0),2000)
        val result=detector.evaluate(f,regional(b).match,listOf(camera),29.0,listOf(a,b),2000,30)
        assertTrue(result.second.accepted);assertTrue(result.first!!.warning!!.announceApproach)
        assertNull(voice.update(30,false,true))
        assertNull(detector.evaluate(f.copy(elapsedMs=3000),regional(b).match,listOf(camera),29.0,listOf(a,b),3000,30).first!!.warning)
        assertEquals(20,voice.update(20,false,true))
    }
    @Test fun directionalRoadWithoutHeadingDoesNotBorrowOppositeLimit() {
        val r=road("way/1",30).copy(tags=mapOf("maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph"))
        val f=fix(Geo.ahead(p,0.0,50.0),1000,null).copy(speedMps=0.0)
        val unknown=regional(r).copy(state=RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,match=RoadMatch(r,0.0,null,.95,0.0))
        val result=evaluate(DrivingLimitPipeline(),f,r,unknown)
        assertNull(result.presentation.mph)
    }
    @Test fun regionalBeforeBoundaryWeakAndDisconnectedMatchesCannotSkipStabilization() {
        for(mode in 0..2) {
            val pipeline=DrivingLimitPipeline();val a=road("way/1",20,p,j)
            val start=if(mode==2) Geo.ahead(j,0.0,60.0) else j
            val b=road("way/2",30,start,Geo.ahead(start,0.0,200.0))
            evaluate(pipeline,fix(Geo.ahead(j,180.0,20.0),1000),a)
            val point=if(mode==0) Geo.ahead(j,0.0,5.0) else Geo.ahead(start,0.0,25.0)
            val match=RoadMatch(b,0.0,0.0,if(mode==1) .75 else .95,0.0)
            val result=evaluate(pipeline,fix(point,2000),b,regional(b).copy(match=match,candidates=listOf(a,b)))
            assertNotEquals("mode $mode must retain uncertainty",30,result.presentation.mph)
        }
    }
    @Test fun reliableRegionalBoundaryCannotOverrideSavedOwnerEvidence() {
        val a=road("way/1",20,p,j);val b=road("way/2",30,j,Geo.ahead(j,0.0,200.0))
        val f=fix(Geo.ahead(j,0.0,25.0),2000)
        val boundary=BoundaryCorrection(a.id,b.id,20,30,j,Geo.ahead(j,0.0,80.0),0.0,5.0,5.0,.95,0.0,1000)
        val observation=BoundaryObservation(a,b,20,30,j,f.point,0.0,5.0,5.0,1000)
        for(stillHere in listOf(false,true)) {
            val pipeline=DrivingLimitPipeline();evaluate(pipeline,fix(Geo.ahead(j,180.0,20.0),1000),a)
            val result=pipeline.evaluate(f,listOf(a,b),emptyList(),emptyMap(),
                if(stillHere) emptyList() else listOf(boundary),if(stillHere) listOf(observation) else emptyList(),2000,2000,regional(b))
            assertEquals(20,result.presentation.mph);assertTrue(result.decision.boundaryApplied)
        }
    }
    @Test fun reverseWayGeometryAndReverseOneWayCanConfirmOnlyLegalOutgoingBoundary() {
        for(bearing in listOf(0.0,180.0)) {
            val a=road("way/1",20,Geo.ahead(j,(bearing+180)%360,100.0),j)
            val end=Geo.ahead(j,bearing,200.0)
            val b=road("way/2",30,end,j).copy(tags=mapOf("maxspeed:backward" to "30 mph","oneway" to "-1"))
            val first=fix(Geo.ahead(j,(bearing+180)%360,20.0),1000,bearing)
            val after=fix(Geo.ahead(j,bearing,25.0),2000,bearing)
            for(legal in listOf(true,false)) {
                val pipeline=DrivingLimitPipeline()
                val am=RoadMatch(a,0.0,0.0,.95,Geo.projection(first.point,a.points).second)
                evaluate(pipeline,first,a,regional(a).copy(match=am))
                val next=if(legal) b else b.copy(tags=b.tags+mapOf("oneway" to "yes"))
                val bm=RoadMatch(next,0.0,0.0,.95,Geo.projection(after.point,next.points).second)
                val result=evaluate(pipeline,after,next,regional(next).copy(match=bm,candidates=listOf(a,next)))
                if(legal) assertEquals(30,result.presentation.mph)
                else assertNotEquals(30,result.presentation.mph)
            }
        }
    }
}
