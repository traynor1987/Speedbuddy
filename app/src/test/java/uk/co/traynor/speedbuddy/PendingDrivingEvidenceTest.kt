package uk.co.traynor.speedbuddy

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class PendingDrivingEvidenceTest {
    @Before fun resetBefore()=DriveBus.set(DriveState())
    @After fun cleanup()=DriveBus.set(DriveState())
    @Test fun speechRemainsRelevantAcrossNormalGpsFixDuringDelayedMatchingButEndsOnTurnOrExpiry() {
        val p=GeoPoint(53.5,-2.8)
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val road=RoadMatch(Road("way/1",null,listOf(p,Geo.ahead(p,0.0,500.0)),mapOf("maxspeed" to "30 mph")),0.0,0.0,.95)
        val camera=Camera("camera",Geo.ahead(p,0.0,200.0),CameraType.SPEED,CameraSource.USER)
        val initial=DriveState(active=true,fix=fix,road=road,limitMph=30,sourceLimitMph=30,roadDecisionElapsedMs=1000,
            limitDecision=LimitDecision(30,reason="confirmed"),alert=Alert(camera,200.0),alertPositionFresh=true,speedMph=20.0)
        DriveBus.set(initial)
        val pending=fix.copy(point=Geo.ahead(p,0.0,10.0),elapsedMs=2000)
        DriveBus.publishLocationSpeed(22.0,pending)
        val state=DriveBus.state.value
        assertTrue(state.limitDecision!!.assumed)
        assertTrue(PendingDrivingEvidence.limitSpeechRelevant(state,30))
        assertEquals(30,PendingDrivingEvidence.cameraLimit(state))
        assertFalse(PendingDrivingEvidence.limitSpeechRelevant(state,30,3001))
        assertNull(PendingDrivingEvidence.cameraLimit(state,3001))
        assertTrue(CameraCueValidity.relevant(state.alert,state.speedMph,state.alertPositionFresh,true,
            CameraEncounters.key(camera),false,30,2,2000,PendingDrivingEvidence.cameraLimit(state)))
        assertEquals(190.0,state.alert!!.distanceM,1.0)
        DriveBus.publishLocationSpeed(22.0,pending.copy(bearing=90.0,elapsedMs=2500))
        assertNull(DriveBus.state.value.alert)
        assertFalse(PendingDrivingEvidence.limitSpeechRelevant(DriveBus.state.value,30))
        DriveBus.set(DriveState());DriveBus.set(initial)
        DriveBus.publishLocationSpeed(22.0,fix.copy(elapsedMs=4000))
        assertNull(DriveBus.state.value.alert);assertNull(DriveBus.state.value.limitMph)
    }
    @Test fun pendingProcessingNeverPromotesAnAlreadyAssumedLimitToNumericSpeech() {
        val p=GeoPoint(53.5,-2.8)
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val road=RoadMatch(Road("way/1",null,listOf(p,Geo.ahead(p,0.0,500.0)),emptyMap()),0.0,0.0,.95)
        val camera=Camera("camera",Geo.ahead(p,0.0,200.0),CameraType.SPEED,CameraSource.USER)
        DriveBus.set(DriveState(active=true,fix=fix,road=road,limitMph=30,roadDecisionElapsedMs=1000,
            limitDecision=LimitDecision(30,reason="bounded assumption",assumed=true),alert=Alert(camera,200.0),alertPositionFresh=true))
        for(t in listOf(2000L,3000L)) {
            DriveBus.publishLocationSpeed(22.0,fix.copy(elapsedMs=t))
            val state=DriveBus.state.value
            assertEquals(30,state.limitMph);assertTrue(state.limitDecision!!.assumed)
            assertFalse(state.pendingConfirmedLimit)
            assertFalse(PendingDrivingEvidence.limitSpeechRelevant(state,30,t))
            assertNull(PendingDrivingEvidence.cameraLimit(state,t))
        }
    }
}
