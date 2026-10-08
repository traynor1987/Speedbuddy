package uk.co.traynor.speedbuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegionalDiagnosticsTest {
    @Test fun retainedDiagnosticIdentifiesProviderInsteadOfOnlyLegacyRequests() {
        val receipt=JSONObject(LimitDiagnostics.snapshot("decision",DriveState()))
        assertTrue("Provider provenance is missing",receipt.has("roadData"))
        assertEquals("None",receipt.getJSONObject("roadData").getString("provider"))
        assertTrue(receipt.getJSONObject("roadData").isNull("confidence"))
    }
    private val p=GeoPoint(53.5,-2.8)
    private val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,500.0)),mapOf("maxspeed" to "30 mph"))
    private fun evaluate(state: RoadProviderState,match: RoadMatch?,legacy: List<Road> = emptyList(),coverage: Boolean?=true,error: String?=null): DriveLimitResult {
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val descriptor=RegionalPackDescriptor("lancashire","Lancashire","v1",1,"2026-10-07T00:00:00Z",1,1,"a".repeat(64),"b".repeat(64),"https://example.test","https://example.test")
        return DrivingLimitPipeline().evaluate(fix,legacy,emptyList(),emptyMap(),emptyList(),emptyList(),1000,10_000,
            RegionalPackMatcher.Result(state,match,"generation",listOfNotNull(match?.road),true,listOf(descriptor),coverage,error))
    }
    @Test fun knownUnknownAndUncertainRegionalDiagnosticsKeepTheirActualAuthority() {
        for(state in listOf(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadProviderState.ROAD_MATCH_UNCERTAIN)) {
            val match=if(state==RoadProviderState.ROAD_MATCH_UNCERTAIN) null else RoadMatch(road.copy(tags=if(state==RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN) emptyMap() else road.tags),0.0,0.0,.95,0.0)
            val result=evaluate(state,match,listOf(road))
            assertEquals("Regional offline",result.roadData.provider);assertEquals(state.name,result.roadData.providerState)
            assertEquals("Covered",result.roadData.coverage);assertEquals("Lancashire",result.roadData.packs.single().name)
            assertNull(result.roadData.fallbackReason)
            val receipt=JSONObject(LimitDiagnostics.snapshot("decision",result.applyTo(DriveState())))
            if(match==null) assertTrue(receipt.getJSONObject("roadData").isNull("confidence"))
            else assertEquals(match.confidence,receipt.getJSONObject("roadData").getDouble("confidence"),0.0)
        }
    }
    @Test fun fallbackNoCoverageAndUnreadablePackRemainDistinct() {
        val fallback=evaluate(RoadProviderState.COVERAGE_UNAVAILABLE,null,listOf(road),false)
        assertEquals("Legacy saved OSM",fallback.roadData.provider);assertEquals("Not covered",fallback.roadData.coverage)
        assertEquals("No regional coverage at this position",fallback.roadData.fallbackReason)
        val none=evaluate(RoadProviderState.COVERAGE_UNAVAILABLE,null,coverage=false)
        assertEquals("None",none.roadData.provider)
        val corrupt=evaluate(RoadProviderState.SERVICE_UNAVAILABLE,null,coverage=null,error="Regional database unreadable")
        assertEquals("Not established",corrupt.roadData.coverage);assertEquals("Regional database unreadable",corrupt.roadData.error)
        assertNull(corrupt.road)
    }
    @Test fun invalidAndFuturePackTimestampsDoNotInventAnAge() {
        assertNull(RegionalPackInfo("Test","test","v1","invalid").ageMs(1000))
        assertNull(RegionalPackInfo("Test","test","v1","2026-10-09T00:00:00Z").ageMs(1000))
        assertEquals(1000L,RegionalPackInfo("Test","test","v1","1970-01-01T00:00:00Z").ageMs(1000))
    }

}
