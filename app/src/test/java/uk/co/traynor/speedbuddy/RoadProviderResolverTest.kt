package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RoadProviderResolverTest {
    @Test fun owner_override_wins_without_erasing_underlying_provider_policy() {
        assertEquals(RoadSource.LOCAL_OWNER,RoadProviderResolver.resolve(30,20,null,LiveRoadState(RoadProviderState.COVERAGE_UNAVAILABLE,false,null,true)).source)
    }
    @Test fun unknown_and_ambiguous_live_responses_do_not_authorise_overpass() {
        for(state in listOf(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadProviderState.ROAD_MATCH_UNCERTAIN))
            assertFalse(RoadProviderResolver.resolve(null,null,null,LiveRoadState(state,true,null,true)).overpassAllowed)
    }
    @Test fun only_real_availability_failure_can_allow_existing_last_resort() {
        assertTrue(RoadProviderResolver.resolve(null,null,null,LiveRoadState(RoadProviderState.COVERAGE_UNAVAILABLE,false,null,true)).overpassAllowed)
        assertTrue(RoadProviderResolver.resolve(null,null,null,LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true)).overpassAllowed)
    }
}
