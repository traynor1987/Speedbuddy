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
    @Test fun regionalUnknownIsTerminalEvenWhenLiveHasANumber() {
        val r=road("merseyside-way",null);val replay=DrivingReplay()
        val state=replay.play(frame(r,1_000,RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadMatch(r,0.0,0.0,.95)),LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,20,false)))
        assertNull(state.limitMph);assertEquals("merseyside-way",state.road?.road?.id)
    }
    @Test fun coverageUnavailableUsesLiveStateThroughTheProductionPipeline() {
        val r=road("no-pack-way",null);val state=DrivingReplay().play(frame(r,1_000,RegionalPackMatcher.Result(RoadProviderState.COVERAGE_UNAVAILABLE,null),LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,30,false)))
        assertEquals(30,state.limitMph)
    }
}
