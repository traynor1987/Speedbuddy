package uk.co.traynor.speedbuddy

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real bus, CameraVoice queue and production playback callbacks; no installed TTS voice is required. */
class AudioPublicationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val point=GeoPoint(53.512345678,-2.812345678)
    private fun state(t: Long,mph: Int?=30): DriveState {
        val decision=LimitDecision(mph,reason=if(mph==null) "Unavailable: No recent confirmed limit" else "Confirmed local road limit")
        return DriveState(active=true,fix=Fix(point,4.0,0.0,1.0,null,t),speedMph=0.0,limitMph=mph,
            roadDecisionElapsedMs=t,limitDecision=decision,limitPresentation=decision,
            road=RoadMatch(Road("way/audio",null,listOf(point,Geo.ahead(point,0.0,400.0)),emptyMap()),0.0,null,.95),
            roadData=RoadDataDiagnostics("Regional offline"))
    }
    private fun field(voice: CameraVoice,name: String,value: Any?)=CameraVoice::class.java.getDeclaredField(name).apply { isAccessible=true }.set(voice,value)
    private fun read(voice: CameraVoice,name: String): Any?=CameraVoice::class.java.getDeclaredField(name).apply { isAccessible=true }.get(voice)
    private fun start(voice: CameraVoice,id: String)=CameraVoice::class.java.getDeclaredMethod("beginUtterance",String::class.java).apply { isAccessible=true }.invoke(voice,id)
    private fun prime(voice: CameraVoice,ticket: FlightSpeechTicket,id: String="current",relevant: ()->Boolean) {
        field(voice,"activeUtterance",id);field(voice,"activeTicket",ticket)
        field(voice,"activeSubmittedAtMs",SystemClock.elapsedRealtime());field(voice,"activeRelevant",relevant)
        field(voice,"activeVoiceAllowed",{true})
        @Suppress("UNCHECKED_CAST") val known=read(voice,"knownUtterances") as MutableMap<String,FlightSpeechTicket>
        known[id]=ticket
    }
    private fun exercise(block: (CameraVoice,Long)->Unit) {
        instrumentation.runOnMainSync {
            DriveBus.set(DriveState());RoadDecisionFlight.recorder.clear()
            val t=SystemClock.elapsedRealtime();val voice=CameraVoice(instrumentation.targetContext) {}
            try { block(voice,t) } finally { voice.close();DriveBus.set(DriveState());RoadDecisionFlight.recorder.clear() }
        }
    }
    private fun ticket(state: DriveState,category: LimitSpeechCategory=LimitSpeechCategory.CURRENT)=
        RoadDecisionFlight.recorder.scheduleSpeech(category,30,if(category==LimitSpeechCategory.CURRENT) LimitSpeechSource.REGIONAL else LimitSpeechSource.CAMERA_TAG,state,SystemClock.elapsedRealtime())

    @Test fun unknownPublicationCancelsSubmittedCurrentSpeechBeforeMatcherOrUiCompletes()=exercise { voice,t ->
        DriveBus.set(state(t));val request=ticket(DriveBus.state.value)
        prime(voice,request) { PendingDrivingEvidence.limitSpeechRelevant(DriveBus.state.value,30,SystemClock.elapsedRealtime()) }
        val listener: (DriveState)->Unit={voice.revalidate(currentRoadOnly=true)}
        DriveBus.addPublicationListener(listener)
        try {
            DriveBus.set(state(t+1,null))
            assertNull(read(voice,"activeUtterance"))
            val cancelled=RoadDecisionFlight.recorder.snapshot().events.last { it.speech!=null }
            assertEquals(SpeechOutcome.CANCELLED_INVALID,cancelled.speech!!.outcome)
            assertNull(cancelled.new.displayedMph)
            start(voice,"current")
            assertEquals(SpeechOutcome.LATE_START_REJECTED,RoadDecisionFlight.recorder.snapshot().events.last().speech!!.outcome)
        } finally { DriveBus.removePublicationListener(listener) }
    }

    @Test fun pendingInitialisationQueueIsDiscardedWhenCurrentRoadBecomesUnknown()=exercise { voice,t ->
        DriveBus.set(state(t));val request=ticket(DriveBus.state.value)
        val relevant={PendingDrivingEvidence.limitSpeechRelevant(DriveBus.state.value,30,SystemClock.elapsedRealtime())}
        field(voice,"pending",QueuedCameraSpeech("Speed limit 30 miles per hour.",t,false,relevant,{true},request))
        DriveBus.set(state(t+1,null));voice.revalidate(currentRoadOnly=true)
        assertNull(read(voice,"pending"))
        assertEquals(SpeechOutcome.CANCELLED_INVALID,RoadDecisionFlight.recorder.snapshot().events.last().speech!!.outcome)
    }

    @Test fun ttsStartRechecksEvidenceEvenWithoutAnyNewPublication()=exercise { voice,t ->
        DriveBus.set(state(t-6001));val request=ticket(DriveBus.state.value)
        prime(voice,request) { PendingDrivingEvidence.limitSpeechRelevant(DriveBus.state.value,30,SystemClock.elapsedRealtime()) }
        start(voice,"current")
        assertNull(read(voice,"activeUtterance"))
        assertFalse(RoadDecisionFlight.recorder.snapshot().events.any { it.speech?.outcome==SpeechOutcome.PLAYBACK_STARTED })
    }

    @Test fun cameraSpecificThirtyCanPlayWithUnknownCurrentRoadWithoutPromotingDisplay()=exercise { voice,t ->
        val camera=Camera("camera-private-id",point,CameraType.SPEED,CameraSource.USER,enforcedMph=30)
        val unknown=state(t,null).copy(alert=Alert(camera,50.0),alertPositionFresh=true)
        DriveBus.set(unknown);val request=ticket(unknown,LimitSpeechCategory.CAMERA)
        prime(voice,request) { CameraCueValidity.relevant(DriveBus.state.value.alert,0.0,true,true,camera.id,false,30,2,1000) }
        voice.revalidate(currentRoadOnly=true);voice.revalidate();start(voice,"current")
        val played=RoadDecisionFlight.recorder.snapshot().events.last().speech!!
        assertEquals(SpeechOutcome.PLAYBACK_STARTED,played.outcome);assertEquals(LimitSpeechCategory.CAMERA,played.category)
        assertNull(played.origin.displayedMph);assertNull(played.playback!!.displayedMph)
        assertNull(DriveBus.state.value.limitMph)
    }

    @Test fun lateStartFromSupersededUtteranceDoesNotStartOrStopTheNewOne()=exercise { voice,t ->
        DriveBus.set(state(t));val old=ticket(DriveBus.state.value)
        prime(voice,old,"old") { true }
        DriveBus.set(state(t,40))
        val newer=RoadDecisionFlight.recorder.scheduleSpeech(LimitSpeechCategory.CURRENT,40,LimitSpeechSource.REGIONAL,DriveBus.state.value,t)
        prime(voice,newer,"new") { true }
        start(voice,"old");assertEquals("new",read(voice,"activeUtterance"))
        assertEquals(SpeechOutcome.LATE_START_REJECTED,RoadDecisionFlight.recorder.snapshot().events.last().speech!!.outcome)
        start(voice,"new");assertEquals(40,RoadDecisionFlight.recorder.snapshot().events.last().speech!!.mph)
    }

    @Test fun beepOnlySupersessionTerminatesDelayedNumericCameraTicket()=exercise { voice,t ->
        DriveBus.set(state(t));val request=ticket(DriveBus.state.value,LimitSpeechCategory.CAMERA)
        field(voice,"delayedTicket",request);field(voice,"focusHeld",true)
        voice.play(CameraAudioCue(null,true))
        assertTrue(RoadDecisionFlight.recorder.snapshot().events.any { it.speech?.ticketId==request.id && it.speech.outcome==SpeechOutcome.SUPERSEDED })
    }

    @Test fun actualPendingTimeoutRecordsExpiryInsteadOfClaimingPlayback()=exercise { voice,t ->
        DriveBus.set(state(t));val request=ticket(DriveBus.state.value)
        field(voice,"pending",QueuedCameraSpeech("Speed limit 30 miles per hour.",t-10001,false,{true},{true},request))
        (read(voice,"pendingTimeout") as Runnable).run()
        assertEquals(SpeechOutcome.EXPIRED,RoadDecisionFlight.recorder.snapshot().events.last().speech!!.outcome)
        assertNull(read(voice,"pending"))
    }
}
