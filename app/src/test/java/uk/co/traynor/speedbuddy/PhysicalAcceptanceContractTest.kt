package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class PhysicalAcceptanceContractTest {
    @Test fun liveKnownPrecedesLegacyButLiveUnknownIsTerminal() {
        for (state in listOf(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadProviderState.ROAD_MATCH_UNCERTAIN)) {
            val live=LiveRoadState(state,state!=RoadProviderState.ROAD_MATCH_UNCERTAIN,if(state==RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN) 20 else null,false)
            val result=RoadProviderResolver.resolveProviderStates(null,null,30,live)
            assertEquals(RoadSource.LIVE,result.source)
            assertEquals(live.limitMph,result.limitMph)
        }
    }
    @Test fun unavailableLiveUsesLegacyWithoutBroadeningOverpassPolicy() {
        val result=RoadProviderResolver.resolveProviderStates(null,null,30,LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,false))
        assertEquals(RoadSource.LEGACY_CACHE,result.source)
        assertEquals(30,result.limitMph)
        assertFalse(result.overpassAllowed)
    }

    @Test fun latencyCanRevalidateSameRoadButNeverPromoteOldGps() {
        val p=GeoPoint(53.0,-2.0)
        val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,1000.0)),mapOf("highway" to "primary","maxspeed" to "30 mph"))
        val requested=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
        val sample=LiveRoadSample(requested,LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,20,false,road.id))
        val current=requested.copy(point=Geo.ahead(p,0.0,125.0),elapsedMs=3000)
        val result=DrivingLimitPipeline().evaluate(current,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),3000,10000,live=sample.forFix(current))
        assertEquals(20,result.source);assertEquals(current,result.fix)
        assertEquals("Live server with legacy geometry",result.roadData.provider)
        assertNull(sample.forFix(current.copy(elapsedMs=16000)))
        assertNull(sample.forFix(current.copy(bearing=90.0)))
        assertNull(sample.forFix(current.copy(point=Geo.ahead(p,0.0,500.0))))
    }
    @Test fun numericOnlyOrDifferentWayCannotOverrideGeometry() {
        val p=GeoPoint(53.0,-2.0)
        val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,1000.0)),mapOf("highway" to "primary"))
        val fix=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
        for(id in listOf(null,"way/999")) {
            val result=DrivingLimitPipeline().evaluate(fix,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10000,live=LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,20,false,id))
            assertNull(result.source);assertNull(result.decision.mph)
        }
    }
    @Test fun replayRepeatedKnownGapAndConnectedNationalBoundaryDoesNotHaveFifteenSecondDelay() {
        for(mph in listOf(20,30,60,70)) {
            val p=GeoPoint(53.0,-2.0)
            val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,2000.0)),mapOf("highway" to "primary","maxspeed" to "$mph mph"))
            val pipeline=DrivingLimitPipeline()
            for(t in 1000L..16000L step 1000L) {
                val fix=Fix(Geo.ahead(p,0.0,100.0+t/1000*5),5.0,5.0,1.0,0.0,t)
                val gap=t%3000L==2000L
                val result=pipeline.evaluate(fix,if(gap) emptyList() else listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),t,10000+t)
                assertEquals(mph,result.decision.mph)
                assertEquals(gap,result.decision.assumed)
                // Fresh completed evidence clears the former presentation recovery latch immediately.
                assertEquals(gap,result.presentation.assumed)
            }
        }
        val p=GeoPoint(53.0,-2.0);val boundary=Geo.ahead(p,0.0,200.0)
        val old=Road("way/1","Route",listOf(p,boundary),mapOf("highway" to "primary","maxspeed" to "40 mph"))
        val next=Road("way/2","Route",listOf(boundary,Geo.ahead(p,0.0,2000.0)),mapOf("highway" to "primary","maxspeed" to "GB:nsl_single"))
        val pipeline=DrivingLimitPipeline()
        val values=(1000L..16000L step 1000L).map { t ->
            val fix=Fix(Geo.ahead(p,0.0,180.0+(t/1000-1)*15),5.0,15.0,1.0,0.0,t)
            pipeline.evaluate(fix,listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),t,10000+t).decision
        }
        assertEquals(40,values.first().mph)
        assertEquals(60,values[5].mph)
        assertTrue(values.drop(5).all { it.mph==60 })
    }

    @Test fun releasedMetadataMatcherIsAcceptedButNumericSyntheticMatcherIsRejected() {
        // Transcribed schema semantics from the production handoff; not downloaded pack bytes.
        for(region in listOf("lancashire","merseyside")) {
            val metadata=mapOf("formatVersion" to "1","matcherVersion" to "\"distance-heading-oneway-continuity-v1\"",
                "dataset" to "{ \"region\" : \"$region\" }", "coverage" to "[]")
            RegionalPackManifest.verifyMetadata(metadata,region)
            assertThrows(IllegalArgumentException::class.java) { RegionalPackManifest.verifyMetadata(metadata+mapOf("matcherVersion" to "1"),region) }
            assertThrows(IllegalArgumentException::class.java) { RegionalPackManifest.verifyMetadata(metadata+mapOf("matcherVersion" to "\"future-v2\""),region) }
            assertThrows(IllegalArgumentException::class.java) { RegionalPackManifest.verifyMetadata(metadata,"wrong-region") }
        }
    }

    @Test fun authenticationRateAndMalformedResponsesNeverAuthorizeOverpass() {
        for(code in listOf(401,403,429)) {
            val client=SpeedBuddyRoadClient({"test"},{ url -> object: java.net.HttpURLConnection(url) {
                override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                override fun getResponseCode()=code
            } })
            val state=client.request(Fix(GeoPoint(53.0,-2.0),5.0,10.0,1.0,0.0,1000))
            assertEquals("HTTP $code",state.error);assertFalse(state.fallbackAllowed);assertNull(state.limitMph)
        }
    }

    @Test fun backgroundRefreshWaitsForLiveAndNeverBypassesKnownUnknownAuthOrRate() {
        val absent=RoadProviderState.COVERAGE_UNAVAILABLE
        assertFalse(RegionalRefreshPolicy.permitsAfterLive(absent,null))
        for(state in listOf(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN,RoadProviderState.ROAD_MATCH_UNCERTAIN))
            assertFalse(RegionalRefreshPolicy.permitsAfterLive(absent,LiveRoadState(state,true,null,true)))
        assertFalse(RegionalRefreshPolicy.permitsAfterLive(absent,LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,false,error="HTTP 401")))
        assertTrue(RegionalRefreshPolicy.permitsAfterLive(absent,LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true)))
        assertFalse(RegionalRefreshPolicy.permitsAfterLive(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,LiveRoadState(RoadProviderState.SERVICE_UNAVAILABLE,false,null,true)))
    }

    @Test fun contradictoryLiveIdentityClearsOwnerAndPriorAssumptionsImmediately() {
        val p=GeoPoint(53.0,-2.0)
        val road=Road("way/123","Test",listOf(p,Geo.ahead(p,0.0,1000.0)),mapOf("highway" to "primary","maxspeed" to "30 mph"))
        val f=Fix(Geo.ahead(p,0.0,100.0),5.0,10.0,1.0,0.0,1000)
        val pipeline=DrivingLimitPipeline()
        assertEquals(30,pipeline.evaluate(f,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),1000,10000).decision.mph)
        val conflict=pipeline.evaluate(f.copy(elapsedMs=1500),listOf(road),listOf(RoadDb.Override(road.id,0.0,20)),emptyMap(),emptyList(),emptyList(),1500,10500,
            live=LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,40,false,"way/999"))
        assertNull(conflict.decision.mph);assertNull(conflict.road)
        assertFalse(conflict.decision.assumed);assertFalse(conflict.decision.ownerApplied)
        assertFalse(conflict.geometryComplete)
    }
}
