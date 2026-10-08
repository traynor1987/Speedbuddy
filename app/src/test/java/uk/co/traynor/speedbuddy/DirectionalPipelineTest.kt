package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class DirectionalPipelineTest {
    @Test fun actualMatcherAndPersistentPipelineHandleGradualReversalWithinSameWay() {
        val point=GeoPoint(53.5,-2.8)
        val road=Road("way/123",null,listOf(Geo.ahead(point,180.0,200.0),Geo.ahead(point,0.0,200.0)),
            mapOf("highway" to "residential","maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph"))
        val matcher=RoadMatcher();val pipeline=DrivingLimitPipeline()
        fun evaluate(bearing: Double,t: Long): DriveLimitResult {
            val fix=Fix(point,5.0,10.0,1.0,bearing,t)
            val match=matcher.match(fix,listOf(road))
            val result=RegionalPackMatcher.Result(if(match==null) RoadProviderState.ROAD_MATCH_UNCERTAIN else RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,match,candidates=listOf(road))
            return pipeline.evaluate(fix,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t,result)
        }
        assertEquals(50,evaluate(180.0,1000).decision.mph)
        evaluate(135.0,2000);evaluate(90.0,3000);evaluate(45.0,4000)
        val forward=evaluate(0.0,5000)
        assertEquals(30,forward.source);assertEquals(30,forward.decision.mph);assertFalse(forward.decision.assumed)
        assertEquals(50,evaluate(180.0,6000).decision.mph)
    }
    @Test fun delayedStillFreshFrameDoesNotMutateNewerPipelineHistory() {
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/123",null,listOf(p,Geo.ahead(p,0.0,500.0)),mapOf("maxspeed" to "30 mph"))
        val pipeline=DrivingLimitPipeline()
        fun evaluate(t: Long,roads: List<Road>)=pipeline.evaluate(Fix(p,5.0,10.0,1.0,0.0,t),roads,emptyList(),emptyMap(),emptyList(),emptyList(),3000,10_000)
        assertEquals(30,evaluate(2000,listOf(road)).decision.mph)
        assertNull(evaluate(1000,listOf(road.copy(tags=mapOf("maxspeed" to "50 mph")))).decision.mph)
        assertEquals(30,evaluate(3000,listOf(road)).decision.mph)
    }
    @Test fun weakActualMatchCannotActivateSavedOwnerCorrection() {
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/123",null,listOf(Geo.ahead(p,180.0,200.0),Geo.ahead(p,0.0,200.0)),mapOf("highway" to "residential"))
        val fix=Fix(Geo.ahead(p,90.0,19.0),5.0,10.0,1.0,0.0,1000)
        val match=RoadMatcher().match(fix,listOf(road))!!
        assertTrue(match.confidence<.7)
        val regional=RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,match,candidates=listOf(road))
        val result=DrivingLimitPipeline().evaluate(fix,emptyList(),listOf(RoadDb.Override("osm:123",0.0,20)),emptyMap(),emptyList(),emptyList(),1000,10_000,regional)
        assertNull(result.decision.mph);assertFalse(result.decision.ownerApplied)
    }
}
