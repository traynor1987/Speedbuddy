package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class AudioDisplayRegressionTest {
    private val point=GeoPoint(53.512345678,-2.812345678)
    private val road=Road("way/audio",null,listOf(point,Geo.ahead(point,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
    private fun confirmed(t: Long=1000,mph: Int=30)=DriveState(active=true,limitMph=mph,
        fix=Fix(point,4.0,0.0,1.0,null,t),road=RoadMatch(road,0.0,null,.95),roadDecisionElapsedMs=t,
        sourceLimitMph=mph,limitDecision=LimitDecision(mph,reason="Confirmed local road limit"),
        limitPresentation=LimitDecision(mph,reason="Confirmed local road limit"),
        roadData=RoadDataDiagnostics("Regional offline",RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN.name))

    @Test fun currentSpeechCannotOutliveConfirmedGpsEvidenceWithoutAnotherPublication() {
        assertTrue(PendingDrivingEvidence.limitSpeechRelevant(confirmed(),30,1000))
        assertFalse(PendingDrivingEvidence.limitSpeechRelevant(confirmed(),30,6001))
    }
    @Test fun numericCurrentSpeechIsInvalidWhilePresentationIsChangingOrUnknown() {
        val state=confirmed().copy(limitPresentation=LimitDecision(null,reason="Resolving connected road transition",changing=true))
        assertFalse(PendingDrivingEvidence.limitSpeechRelevant(state,30,1000))
    }
    @Test fun cameraSpecificNumberHasDistinctWordingEvenWithUnknownCurrentRoad() {
        val camera=Camera("camera",point,CameraType.SPEED,CameraSource.USER,enforcedMph=30)
        assertEquals(30,CameraLimits.resolve(camera,null,null))
        assertEquals("Speed camera ahead. Camera limit 30 miles per hour.",CameraAnnouncement.text(camera,null))
    }

    @Test fun freshConfirmedThirtyReplacesUnknownAndIsImmediatelyEligibleForSpeech() {
        val f=confirmed().fix!!
        val pipeline=DrivingLimitPipeline()
        val unknown=pipeline.evaluate(f,emptyList(),emptyList(),emptyMap(),emptyList(),emptyList(),1000,1000,
            RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,coverage=true))
        assertNull(unknown.presentation.mph)
        val next=f.copy(elapsedMs=1001)
        val result=pipeline.evaluate(next,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),1001,1001,
            RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(road,0.0,null,.95),candidates=listOf(road),coverage=true))
        val state=result.applyTo(DriveState(active=true))
        assertEquals(30,state.limitMph);assertFalse(state.limitPresentation!!.assumed)
        assertEquals(30,CurrentRoadSpeech.candidate(state,1001))
    }

    @Test fun deferredThirtyIsCancelledBySubsecondUnknownEvenIfThirtyQuicklyReturns() {
        var at=1000L;val recorder=RoadFlightRecorder(clock={at});val voice=CurrentRoadVoice(recorder)
        voice.update(confirmed(1000,20),1000,false,true)
        at=1001;assertNull(voice.update(confirmed(1001,30),1001,true,true))
        at=1002;voice.onPublication(confirmed(1002).copy(limitMph=null,limitDecision=LimitDecision(null,reason="Unknown"),limitPresentation=LimitDecision(null,reason="Unknown")),1002)
        at=1003;assertNull(voice.update(confirmed(1003,30),1003,false,true))
        val events=recorder.snapshot().events.mapNotNull { it.speech }
        assertEquals(listOf(SpeechOutcome.SCHEDULED,SpeechOutcome.DEFERRED,SpeechOutcome.CANCELLED_INVALID),events.map { it.outcome })
        assertTrue(events.all { it.mph==30 && it.category==LimitSpeechCategory.CURRENT })
    }

    @Test fun sameLimitIdentityChangeDoesNotSpeakAndRealThirtyToFortyDoes() {
        val voice=CurrentRoadVoice(RoadFlightRecorder(clock={1000}))
        assertNull(voice.update(confirmed(),1000,false,true))
        val same=confirmed(1001).copy(road=RoadMatch(road.copy(id="way/other"),0.0,null,.95))
        assertNull(voice.update(same,1001,false,true))
        val changed=voice.update(confirmed(1002,40),1002,false,true)!!
        assertEquals(40,changed.mph);assertEquals(30,same.limitMph)
    }

    @Test fun upcomingNumericLimitNeverBecomesCurrentSpeech() {
        val upcoming=UpcomingLimit(40,100.0,false,roadId="way/upcoming")
        val unknown=confirmed().copy(limitMph=null,limitDecision=LimitDecision(null,upcoming=upcoming,reason="Unknown"),
            limitPresentation=LimitDecision(null,reason="Unknown"),upcoming=upcoming)
        assertNull(CurrentRoadSpeech.candidate(unknown,1000))
        assertEquals(30,CurrentRoadSpeech.candidate(confirmed().copy(upcoming=upcoming),1000))
        val recorder=RoadFlightRecorder(clock={1000})
        val ticket=recorder.scheduleSpeech(LimitSpeechCategory.UPCOMING,40,LimitSpeechSource.UPCOMING_GEOMETRY,unknown,1000)
        assertEquals(LimitSpeechCategory.UPCOMING,ticket.category)
        assertNull(ticket.origin.displayedMph)
    }

    @Test fun legacyCompatibleLiveAndOwnerDecisionsKeepTheirRealProvenance() {
        for(live in listOf<LiveRoadState?>(null,LiveRoadState(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,true,30,false,road.id))) {
            val fix=confirmed().fix!!.copy(bearing=0.0,speedMps=5.0)
            val result=DrivingLimitPipeline().evaluate(fix,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),1000,1000,null,live)
            val state=result.applyTo(DriveState(active=true))
            assertEquals(30,state.limitMph);assertEquals(30,CurrentRoadSpeech.candidate(state,1000))
            assertEquals(if(live==null) LimitSpeechSource.LEGACY else LimitSpeechSource.LIVE,CurrentRoadSpeech.source(state))
        }
        assertEquals(LimitSpeechSource.OWNER_CORRECTION,CurrentRoadSpeech.source(confirmed().let { it.copy(limitDecision=it.limitDecision!!.copy(ownerApplied=true)) }))
        assertEquals(LimitSpeechSource.OWNER_BOUNDARY,CurrentRoadSpeech.source(confirmed().let { it.copy(limitDecision=it.limitDecision!!.copy(boundaryApplied=true)) }))
    }

    @Test fun genuineAssumptionCannotScheduleSpeechAndFreshConfirmationClearsIt() {
        val assumed=confirmed().let { it.copy(limitDecision=it.limitDecision!!.copy(assumed=true),limitPresentation=it.limitPresentation!!.copy(assumed=true)) }
        assertNull(CurrentRoadSpeech.candidate(assumed,1000))
        assertEquals(30,CurrentRoadSpeech.candidate(confirmed(1001),1001))
    }

    @Test fun rapidStationaryFluctuationsDoNotReplayAnInvalidatedNumber() {
        val recorder=RoadFlightRecorder(clock={1000});val voice=CurrentRoadVoice(recorder)
        voice.update(confirmed(1000,20),1000,false,true)
        repeat(30) { i ->
            val t=1001L+i*3
            voice.update(confirmed(t,30),t,true,true)
            voice.onPublication(confirmed(t+1).copy(limitMph=null,limitDecision=null,limitPresentation=null),t+1)
            assertNull(voice.update(confirmed(t+2,30),t+2,false,true))
        }
        assertEquals(1,recorder.snapshot().events.count { it.speech?.outcome==SpeechOutcome.CANCELLED_INVALID })
    }

    @Test fun newestDeferredNumberSupersedesOlderCueWithoutSuppressingLegitimateChange() {
        val recorder=RoadFlightRecorder(clock={1000});val voice=CurrentRoadVoice(recorder)
        voice.update(confirmed(1000,20),1000,false,true)
        voice.update(confirmed(1001,30),1001,true,true)
        voice.update(confirmed(1002,40),1002,true,true)
        assertEquals(40,voice.update(confirmed(1003,40),1003,false,true)!!.mph)
        assertTrue(recorder.snapshot().events.any { it.speech?.mph==30 && it.speech.outcome==SpeechOutcome.CANCELLED_INVALID })
    }

    @Test fun speechAndRoadEventsShareSequenceAndTimestampWithoutLeakingSensitiveFields() {
        var at=1000L;val recorder=RoadFlightRecorder(clock={at})
        val camera=Camera("personal-camera-key",point,CameraType.SPEED,CameraSource.USER,enforcedMph=30,note="password secret")
        val safeState=confirmed().copy(alert=Alert(camera,50.0))
        recorder.record(FlightStage.PUBLISHED,safeState,"verified",1000)
        val ticket=recorder.scheduleSpeech(LimitSpeechCategory.CURRENT,30,LimitSpeechSource.REGIONAL,safeState,1000)
        at=1001;recorder.speech(ticket,SpeechOutcome.SUBMITTED,safeState,1001)
        at=1002;recorder.speech(ticket,SpeechOutcome.PLAYBACK_STARTED,safeState,1002)
        at=1003;recorder.speech(ticket,SpeechOutcome.COMPLETED,safeState,1003)
        val events=recorder.snapshot().events
        assertEquals((1L..5L).toList(),events.map { it.sequence })
        assertEquals(listOf(1000L,1000L,1001L,1002L,1003L),events.map { it.atMs })
        assertTrue(events.drop(1).all { it.new.fixSequence==events.first().new.fixSequence && it.new.roadId==events.first().new.roadId })
        val played=events[3].speech!!
        assertEquals(30,played.origin.displayedMph);assertEquals(30,played.playback!!.displayedMph)
        assertEquals(1000L,played.evidenceElapsedMs);assertEquals(2L,played.evidenceAgeMs)
        val report=recorder.snapshot().report()
        for(secret in listOf("53.512345678","-2.812345678","way/audio","personal-camera-key","password secret","Authorization")) assertFalse(secret,report.contains(secret))
    }

    @Test fun cameraAndOverspeedSpeechRemainCameraSpecificWhenCurrentRoadIsUnknown() {
        val camera=Camera("camera",point,CameraType.SPEED,CameraSource.USER,enforcedMph=30)
        val unknown=confirmed().copy(limitMph=null,limitDecision=LimitDecision(null,reason="Unknown"),limitPresentation=LimitDecision(null,reason="Unknown"),
            alert=Alert(camera,50.0),alertPositionFresh=true)
        assertNull(CurrentRoadSpeech.candidate(unknown,1000))
        assertEquals(30,PendingDrivingEvidence.cameraLimit(unknown,1000))
        for(speeding in listOf(false,true)) {
            val cue=CameraAudioCue.from(camera,CameraWarning(true,false,speeding,30),true)
            assertEquals(30,cue.numericLimit);assertEquals(LimitSpeechSource.CAMERA_TAG,cue.evidenceSource)
            assertEquals(if(speeding) LimitSpeechCategory.OVERSPEED else LimitSpeechCategory.CAMERA,cue.category)
            assertTrue(cue.speech!!.contains("Camera limit 30"))
        }
    }

    @Test fun audioEventsShareTheExistingFiveHundredEventBudget() {
        val recorder=RoadFlightRecorder(clock={1000})
        repeat(600) { i -> recorder.scheduleSpeech(LimitSpeechCategory.CURRENT,30,LimitSpeechSource.REGIONAL,confirmed(1000L+i),1000L+i) }
        assertEquals(500,recorder.snapshot().events.size)
        assertEquals(600L,recorder.snapshot().events.last().sequence)
        recorder.clear();assertTrue(recorder.snapshot().events.isEmpty())
    }
}
