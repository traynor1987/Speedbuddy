package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RoadFlightRecorderTest {
    private val p=GeoPoint(53.512345678,-2.812345678)
    private fun state(t: Long=1000, mph: Int?=30, assumed: Boolean=false)=DriveState(active=true,
        fix=Fix(p,5.0,0.0,1.0,null,t),speedMph=0.0,limitMph=mph,
        limitDecision=LimitDecision(mph,reason=if(mph==null) "Unavailable: No recent confirmed limit" else "Road maxspeed",assumed=assumed),
        limitPresentation=LimitDecision(mph,reason="Road maxspeed",assumed=assumed),
        roadData=RoadDataDiagnostics(provider="Regional offline"))

    @Test fun subsecondIntermediateDecisionsAreCapturedBeforeAnyUiSubscriber() {
        var at=1000L
        val recorder=RoadFlightRecorder(clock={at})
        recorder.record(FlightStage.PUBLISHED,state(),"verified",1000)
        at+=10;recorder.record(FlightStage.PUBLISHED,state(1010,30,true),"pending",1010)
        at+=10;recorder.record(FlightStage.PUBLISHED,state(1020,null),"verified",1020)
        at+=10;recorder.record(FlightStage.PUBLISHED,state(1030),"verified",1030)
        val events=recorder.snapshot().events
        assertEquals(4,events.size)
        assertEquals(listOf("confirmed","assumed","unknown","confirmed"),events.map { it.new.decision.status })
        assertEquals("assumed",events[2].previous!!.decision.status)
        assertEquals(listOf(1000L,1010L,1020L,1030L),events.map { it.atMs })
        assertEquals(3,recorder.snapshot().decisionChanges(1030))
    }

    @Test fun identicalPublicationsAndUiReadsNeverCreateEvents() {
        val recorder=RoadFlightRecorder(clock={1000})
        repeat(100) { recorder.record(FlightStage.PUBLISHED,state(),"verified",1000);recorder.snapshot() }
        assertEquals(1,recorder.snapshot().events.size)
    }

    @Test fun bufferIsBoundedAndClearDoesNotReuseSequences() {
        var at=0L;val recorder=RoadFlightRecorder(clock={at})
        repeat(650) { at++;recorder.record(FlightStage.PUBLISHED,state(at,if(it%2==0) 20 else 30),"verified",at) }
        assertEquals(500,recorder.snapshot().events.size)
        assertEquals(151L,recorder.snapshot().events.first().sequence)
        recorder.clear();assertTrue(recorder.snapshot().events.isEmpty())
        recorder.record(FlightStage.PUBLISHED,state(1000),"verified",1000)
        assertEquals(651L,recorder.snapshot().events.single().sequence)
    }

    @Test fun exportUsesWhitelistAndNeverIncludesUntrustedReasonsOrRoadIdentity() {
        val recorder=RoadFlightRecorder(clock={1000})
        val secret="Bearer owner-secret password signing-key Authorization 53.512345678 -2.812345678 James"
        val road=Road("way/secret-person-123","James",listOf(p,Geo.ahead(p,0.0,100.0)),mapOf("token" to secret))
        val unsafe=state().copy(road=RoadMatch(road,0.0,null,.95),
            limitDecision=LimitDecision(30,reason=secret,evidencePoint=p),
            roadData=RoadDataDiagnostics(provider="Regional offline",error=secret,fallbackReason=secret))
        recorder.record(FlightStage.PUBLISHED,unsafe,"verified",1000)
        val report=recorder.snapshot().report()
        for(value in listOf(secret,"owner-secret","secret-person-123","53.512345678","-2.812345678","James","signing-key")) assertFalse(value,report.contains(value))
        assertTrue(report.contains("road-"));assertTrue(report.contains("0.95"))
        val other=RoadFlightRecorder(clock={1000})
        other.record(FlightStage.PUBLISHED,unsafe,"verified",1000)
        assertNotEquals(recorder.snapshot().events.single().new.roadId,other.snapshot().events.single().new.roadId)
    }

    @Test fun stationaryDriftAndConfidenceFluctuationsRetainEverySourceSample() {
        val recorder=RoadFlightRecorder(clock={1000})
        val road=Road("way/1",null,listOf(p,Geo.ahead(p,0.0,100.0)),mapOf("maxspeed" to "30 mph"))
        repeat(4) { i ->
            val fix=state(1000L+i).fix!!.copy(point=Geo.ahead(p,90.0,i.toDouble()))
            recorder.record(FlightStage.REGIONAL,state(1000L+i).copy(fix=fix,road=RoadMatch(road,i.toDouble(),null,.9-i*.1)),"regional result",1000L+i,"pack-generation")
        }
        val events=recorder.snapshot().events
        assertEquals(4,events.size);assertEquals(listOf(1L,2L,3L,4L),events.map { it.new.fixSequence })
        assertEquals(listOf(.9,.8,.7,.6).map { it.toFloat() },events.map { it.new.matchConfidence!!.toFloat() })
        assertTrue(events.all { it.new.speedMph==0.0 && !it.new.headingValid && it.new.generation!!.startsWith("generation-") })
        assertEquals(0,recorder.snapshot().decisionChanges())
    }

    @Test fun ageOnlyAndCameraOnlyUpdatesDoNotCountAsRoadDecisionChanges() {
        val recorder=RoadFlightRecorder(clock={1000})
        val first=state().copy(limitDecision=state().limitDecision!!.copy(evidenceElapsedMs=500),
            limitPresentation=state().limitPresentation!!.copy(evidenceElapsedMs=500))
        recorder.record(FlightStage.PUBLISHED,first,"verified",1000)
        recorder.record(FlightStage.PUBLISHED,first.copy(decision=CameraDecision(null,10.0,true,"New camera"),overspeed=true),"state published",2000)
        assertEquals(1,recorder.snapshot().events.size)
        assertEquals(0,recorder.snapshot().decisionChanges())
        assertEquals(500L,recorder.snapshot().events.single().new.decision.evidenceAgeMs)
    }

    @Test fun concurrentSourcesRemainOrderedAndNeverLoseIntermediateEvents() {
        val recorder=RoadFlightRecorder(clock={1000})
        val workers=(0..7).map { worker -> Thread {
            repeat(100) { i -> recorder.record(FlightStage.PUBLISHED,state((worker*100+i).toLong(),if(i%2==0) 20 else 30),"verified",1000) }
        } }
        workers.forEach { it.start() };workers.forEach { it.join() }
        val events=recorder.snapshot().events
        assertEquals(500,events.size);assertEquals((301L..800L).toList(),events.map { it.sequence })
        events.zipWithNext().forEach { (a,b) -> assertEquals(a.new,b.previous);assertTrue(b.atMs>=a.atMs) }
    }

    @Test fun clockRegressionDoesNotMoveEventTimestampsBackwards() {
        var at=1000L;val recorder=RoadFlightRecorder(clock={at})
        recorder.record(FlightStage.PUBLISHED,state(),"verified",1000)
        at=900;recorder.record(FlightStage.PUBLISHED,state(1001,null),"verified",1001)
        assertEquals(listOf(1000L,1000L),recorder.snapshot().events.map { it.atMs })
    }

    @Test fun thirtySecondCountExpiresWithoutRecordingUiTicks() {
        var at=1000L;val recorder=RoadFlightRecorder(clock={at})
        recorder.record(FlightStage.PUBLISHED,state(),"verified",1000)
        at=1010;recorder.record(FlightStage.PUBLISHED,state(1010,null),"verified",1010)
        at=31_011
        assertEquals(0,recorder.snapshot().decisionChanges());assertEquals(2,recorder.snapshot().events.size)
    }

    @Test fun freezingKeepsInspectionAndReportFixedWhileLiveHistoryAdvances() {
        DiagnosticsInspection.unfreeze()
        try {
            var at=1000L;val recorder=RoadFlightRecorder(clock={at})
            recorder.record(FlightStage.PUBLISHED,state(),"verified",1000)
            DiagnosticsInspection.freeze(state(),recorder.snapshot(),1000,2000)
            val frozen=DiagnosticsInspection.state.value!!
            val report=frozen.history.report()
            at=1010;recorder.record(FlightStage.PUBLISHED,state(1010,20),"verified",1010)
            assertEquals(30,DiagnosticsInspection.state.value!!.state.limitMph)
            assertEquals(report,DiagnosticsInspection.state.value!!.history.report())
            assertEquals(20,recorder.snapshot().current!!.decision.mph)
            DiagnosticsInspection.unfreeze();assertNull(DiagnosticsInspection.state.value)
        } finally { DiagnosticsInspection.unfreeze() }
    }

    @Test fun pipelineRecordsEvidenceBeforePresentationAndRejectsStaleFixes() {
        val recorder=RoadFlightRecorder(clock={1000})
        val pipeline=DrivingLimitPipeline(recorder=recorder)
        val road=Road("way/1",null,listOf(p,Geo.ahead(p,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
        fun evaluate(t: Long)=pipeline.evaluate(state(t).fix!!,listOf(road),emptyList(),emptyMap(),emptyList(),emptyList(),t,t,
            RegionalPackMatcher.Result(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,RoadMatch(road,0.0,null,.95)))
        assertFalse(evaluate(2000).presentation.assumed)
        val first=recorder.snapshot().events
        assertEquals(listOf(FlightStage.DECISION,FlightStage.PRESENTATION),first.map { it.stage })
        assertEquals(30,first[0].new.sourceMph);assertEquals("confirmed",first[0].new.decision.status)
        assertEquals(30,first[1].new.displayedMph)
        assertNull(evaluate(1000).presentation.mph)
        assertEquals(FlightStage.REJECTED,recorder.snapshot().events.last().stage)
        assertEquals(30,evaluate(3000).presentation.mph)
        assertFalse(evaluate(4000).presentation.assumed)
    }

    @Test fun sourceAndPresentationDisagreementIsNotInferredFromDisplayText() {
        val recorder=RoadFlightRecorder(clock={1000})
        val raw=state().copy(limitMph=null,limitPresentation=LimitDecision(null,reason="Resolving connected road transition",changing=true))
        recorder.record(FlightStage.PUBLISHED,raw,"verified",1000)
        val captured=recorder.snapshot().current!!
        assertEquals(30,captured.decision.mph);assertEquals("confirmed",captured.decision.status)
        assertTrue(captured.presentation.changing);assertNull(captured.displayedMph)
    }

    @Test fun unevaluatedStagesNeverInventUnknownDecisionsOrDisplayStates() {
        val recorder=RoadFlightRecorder(clock={1000})
        recorder.record(FlightStage.GPS,DriveState(fix=state().fix),"gps accepted",1000)
        recorder.record(FlightStage.REGIONAL,DriveState(fix=state().fix,sourceLimitMph=30),"regional result",1000)
        recorder.record(FlightStage.DECISION,state().copy(limitPresentation=null,limitMph=null),"pipeline evidence",1000)
        val events=recorder.snapshot().events
        for(event in events.take(2)) {
            assertEquals("not evaluated",event.new.decision.status)
            assertTrue(event.new.json().isNull("decision"));assertTrue(event.new.json().isNull("presentation"))
        }
        assertEquals("confirmed",events.last().new.decision.status)
        assertTrue(events.last().new.json().isNull("presentation"))
        assertFalse(events.last().new.json().getBoolean("displayRecorded"))
    }

    @Test fun gpsSpeedIsRecordedSeparatelyFromFilteredDisplaySpeed() {
        val recorder=RoadFlightRecorder(clock={1000})
        recorder.record(FlightStage.GPS,state().let { it.copy(fix=it.fix!!.copy(speedMps=5.0),speedMph=3.2) },"gps accepted",1000)
        val raw=recorder.snapshot().events.single().new
        assertEquals(5.0*MPS_TO_MPH,raw.speedMph!!,.0001)
        assertEquals(3.2,raw.filteredSpeedMph!!,.0001)
        assertTrue(raw.filteredSpeedRecorded)
    }
}
