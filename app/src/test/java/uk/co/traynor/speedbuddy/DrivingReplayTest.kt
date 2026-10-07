package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class DrivingReplayTest {
    private val origin=GeoPoint(53.5,-2.8)
    private fun road(id:String, mph:Int?)=Road(id,id,listOf(origin,Geo.ahead(origin,0.0,1000.0)),mapOf("highway" to "residential")+(mph?.let { mapOf("maxspeed" to "$it mph") } ?: emptyMap()))
    private fun frame(road:Road, at:Long, regional:RegionalPackMatcher.Result?=null, live:LiveRoadState?=null)=ReplayFrame(
        Fix(Geo.ahead(origin,0.0,100.0),5.0,10.0,1.0,0.0,at),listOf(road),regional=regional,live=live,now=at,wallNow=10_000+at)

    @Test fun replayUsesRegionalKnownLimitAndPublishesTheSameDriveBusState() {
        val r=road("lancashire-way",20);val replay=DrivingReplay()
        val state=replay.play(frame(r,1_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(r,0.0,0.0,.95))))
        assertEquals(20,state.limitMph);assertEquals(state,DriveBus.state.value)
    }
    @Test fun replayCrossesFromLancashireToMerseysideUsingTheNewRegionalMatch() {
        val replay=DrivingReplay();val lancashire=road("lancashire-way",30);val merseyside=road("merseyside-way",20)
        replay.play(frame(lancashire,1_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(lancashire,0.0,0.0,.95))))
        val state=replay.play(frame(merseyside,2_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(merseyside,0.0,0.0,.95))))
        assertEquals("merseyside-way",state.road?.road?.id);assertEquals(20,state.sourceLimitMph)
    }
    @Test fun replayCrossesFromMerseysideToLancashireUsingTheNewRegionalMatch() {
        val replay=DrivingReplay();val merseyside=road("merseyside-way",40);val lancashire=road("lancashire-way",60)
        replay.play(frame(merseyside,1_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(merseyside,0.0,0.0,.95))))
        val state=replay.play(frame(lancashire,2_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(lancashire,0.0,0.0,.95))))
        assertEquals("lancashire-way",state.road?.road?.id);assertEquals(60,state.sourceLimitMph)
    }
    @Test fun regionalUnknownIsTerminalEvenWhenLiveHasANumber() {
        val r=road("merseyside-way",null);val replay=DrivingReplay()
        val state=replay.play(frame(r,1_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadMatch(r,0.0,0.0,.95)),LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,20,false)))
        assertNull(state.limitMph);assertEquals("merseyside-way",state.road?.road?.id)
    }
    @Test fun coverageUnavailableUsesLiveStateThroughTheProductionPipeline() {
        val r=road("no-pack-way",null);val state=DrivingReplay().play(frame(r,1_000,RegionalPackMatcher.Result(RoadProviderState.COVERAGE_UNAVAILABLE,null),LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,30,false)))
        assertEquals(30,state.limitMph)
    }
    @Test fun serviceUnavailableWithoutALiveLimitStaysTruthfullyUnknown() {
        val r=road("no-service-way",null);val state=DrivingReplay().play(frame(r,1_000,
            RegionalPackMatcher.Result(RoadProviderState.SERVICE_UNAVAILABLE,null),null))
        assertNull(state.limitMph);assertNull(state.sourceLimitMph)
    }
    @Test fun uncertainRegionalMatchIsAlsoTerminalAndDoesNotUseLiveNumber() {
        val r=road("parallel-road",null);val state=DrivingReplay().play(frame(r,1_000,
            RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCH_UNCERTAIN,RoadMatch(r,0.0,0.0,.4)),
            LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,40,false)))
        assertNull(state.limitMph);assertEquals("parallel-road",state.road?.road?.id)
    }
    @Test fun ownerOverrideWinsOverRegionalAndLiveProviders() {
        val r=road("owner-way",30);val replay=DrivingReplay()
        val value=replay.play(ReplayFrame(Fix(Geo.ahead(origin,0.0,100.0),5.0,10.0,1.0,0.0,1_000),listOf(r),
            overrides=listOf(RoadDb.Override(r.id,0.0,20)),regional=RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(r,0.0,0.0,.95)),
            live=LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,40,false),now=1_000,wallNow=11_000))
        assertEquals(20,value.limitMph);assertTrue(value.limitDecision!!.ownerApplied)
    }
    @Test fun boundedContinuityExpiresAfterTwoSecondsAndThirtyMetres() {
        fun fix(m: Double,at: Long)=Fix(Geo.ahead(origin,0.0,m),5.0,10.0,1.0,0.0,at)
        val replay=DrivingReplay();val known=road("continuity-way",30)
        assertEquals(30,replay.play(ReplayFrame(fix(100.0,1_000),listOf(known),now=1_000,wallNow=11_000)).limitMph)
        val brief=replay.play(ReplayFrame(fix(110.0,2_000),emptyList(),now=2_000,wallNow=12_000))
        assertEquals(30,brief.limitMph);assertTrue(brief.limitDecision!!.assumed)
        val expired=replay.play(ReplayFrame(fix(115.0,3_001),emptyList(),now=3_001,wallNow=13_001))
        assertNull(expired.limitMph);assertFalse(expired.limitDecision!!.assumed)
    }
    @Test fun boundedContinuityExpiresAfterThirtyMetresBeforeItsTimeLimit() {
        fun fix(m: Double,at: Long)=Fix(Geo.ahead(origin,0.0,m),5.0,10.0,1.0,0.0,at)
        val replay=DrivingReplay();val known=road("distance-bound-way",30)
        assertEquals(30,replay.play(ReplayFrame(fix(100.0,1_000),listOf(known),now=1_000,wallNow=11_000)).limitMph)
        val expired=replay.play(ReplayFrame(fix(131.0,1_500),emptyList(),now=1_500,wallNow=11_500))
        assertNull(expired.limitMph);assertTrue(expired.limitDecision!!.reason.contains("30 metres"))
    }
    @Test fun authoritativeChangeReplacesAnAssumptionAndAndroidAutoReadsTheSharedBusOnly() {
        val replay=DrivingReplay();val thirty=road("lancashire-boundary",30);val twenty=road("merseyside-boundary",20)
        replay.play(frame(thirty,1_000))
        val state=replay.play(frame(twenty,2_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(twenty,0.0,0.0,.95))))
        assertEquals(20,state.sourceLimitMph);assertEquals(state,DriveBus.state.value)
        // Android Auto subscribes to DriveBus; replay has no Auto matcher or resolver.
        assertEquals("merseyside-boundary",DriveBus.state.value.road?.road?.id)
    }    @Test fun knownMobileEnforcementZoneIsNotAnActiveCameraButAnActiveReportIs() {
        val r=road("camera-way",30);val reportPoint=Geo.ahead(origin,0.0,250.0)
        val active=MobileReport("mobile:replay",reportPoint,1_000,1_000,7_201_000,120,0.0,"camera-way").asCamera()
        val replay=DrivingReplay()
        val zoneOnly=replay.play(frame(r,1_000).copy(mobileEnforcementZones=listOf(
            MobileEnforcementZone("zone:replay",reportPoint,"camera-way"))))
        assertTrue(zoneOnly.mobileEnforcementZones.single().known)
        assertNull(zoneOnly.alert)
        val reported=replay.play(frame(r,2_000).copy(cameras=listOf(active),speedMph=22.0))
        assertEquals(CameraType.MOBILE,reported.alert?.camera?.type)
        assertTrue(AndroidAutoPresenter.present(DriveBus.state.value).camera!!.contains("MOBILE CAMERA"))
    }
}
