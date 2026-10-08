package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RegionalFailureIsolationTest {
    private val p=GeoPoint(53.5,-2.8)
    private val road=Road("way/123","Test",listOf(Geo.ahead(p,180.0,500.0),Geo.ahead(p,0.0,500.0)),mapOf("highway" to "residential","maxspeed" to "30 mph"))
    private fun fix(t: Long)=Fix(p,5.0,10.0,1.0,0.0,t)
    @Test fun liveKnownWithoutLocalGeometryNeverInventsNumericAuthority() {
        val live=LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,50,false)
        val result=DrivingLimitPipeline().evaluate(fix(1000),emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10_000,live=live)
        assertNull(result.road);assertNull(result.source);assertNull(result.decision.mph)
    }
    @Test fun knownResponseWithNoMatchIsRejectedAsInconsistent() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveRoadStateParser.parse("""{"matched":false,"limit":{"mph":50},"providerState":"ROAD_MATCHED_LIMIT_KNOWN"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveRoadStateParser.parse("""{"matched":true,"limit":{"mph":50},"providerState":"ROAD_MATCHED_LIMIT_UNKNOWN"}""")
        }
    }
    @Test fun activationGenerationResetsPriorNumericEvidenceEvenOnTheSameWay() {
        val pipeline=DrivingLimitPipeline()
        fun result(t: Long,mph: Int?,generation: String): DriveLimitResult {
            val f=fix(t);val r=road.copy(tags=if(mph==null) emptyMap() else road.tags+mapOf("maxspeed" to "$mph mph"))
            val match=RoadMatch(r,0.0,0.0,.95,0.0)
            val regional=RegionalPackMatcher.Result(if(mph==null) RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN else RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,match,generation,listOf(r))
            return pipeline.evaluate(f,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000,regional)
        }
        assertEquals(30,result(1000,30,"pack-a").decision.mph)
        assertNull(result(2000,null,"pack-b").decision.mph)
        assertEquals(20,result(3000,20,"pack-c").decision.mph)
    }
    @Test fun unavailableNetworkCannotOverrideTerminalRegionalUnknown() {
        val f=fix(1000);val match=RoadMatch(road.copy(tags=emptyMap()),0.0,0.0,.95,0.0)
        val regional=RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,match,"pack",listOf(match.road))
        val result=DrivingLimitPipeline().evaluate(f,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10_000,regional,
            LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true))
        assertNull(result.source);assertNull(result.decision.mph)
    }
    @Test fun liveTimeoutAndOfflineFailuresAreDisconnectedAndSurfaceAsUnavailable() {
        for(error in listOf(java.net.SocketTimeoutException("timeout"),java.net.UnknownHostException("offline"))) {
            var disconnected=false
            val client=SpeedBuddyRoadClient(credential={"test"},open={url -> object: java.net.HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() { disconnected=true }
                override fun usingProxy()=false
                override fun getResponseCode(): Int { throw error }
            } })
            val outcome=runCatching { client.request(fix(1000)) }.getOrElse { LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true) }
            assertTrue(disconnected);assertEquals(RoadProviderState.SERVICE_UNAVAILABLE,outcome.state);assertNull(outcome.limitMph)
        }
    }
}
