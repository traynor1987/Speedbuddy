package uk.co.traynor.speedbuddy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import android.os.SystemClock

class RoadDecisionMonitorTest {
    @get:Rule val compose=createComposeRule()
    private val p=GeoPoint(53.512345678,-2.812345678)
    private val road=Road("way/diagnostic-test",null,listOf(p,Geo.ahead(p,0.0,300.0)),mapOf("maxspeed" to "30 mph"))
    private fun state(t: Long,mph: Int=30)=DriveState(active=true,speedMph=0.0,fix=Fix(p,5.0,0.0,1.0,null,t),
        road=RoadMatch(road,0.0,null,.95),limitMph=mph,sourceLimitMph=mph,
        limitDecision=LimitDecision(mph,reason="Confirmed local road limit"),
        limitPresentation=LimitDecision(mph,reason="Confirmed local road limit"),roadDecisionElapsedMs=t,
        roadData=RoadDataDiagnostics("Regional offline",RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN.name))
    @Before fun reset() { DriveBus.set(DriveState());RoadDecisionFlight.recorder.clear();DiagnosticsInspection.unfreeze() }
    @After fun clear() { DiagnosticsInspection.unfreeze();DriveBus.set(DriveState());RoadDecisionFlight.recorder.clear() }

    @Test fun pendingAndVerifiedPublicationsAreCapturedEvenWhenUiCannotRenderBetweenThem() {
        val t=SystemClock.elapsedRealtime()
        val first=state(t);DriveBus.set(first)
        DriveBus.publishLocationSpeed(0.0,first.fix!!.copy(elapsedMs=t+1))
        DriveBus.set(state(t+1))
        val publications=RoadDecisionFlight.recorder.snapshot().events.filter { it.stage==FlightStage.PUBLISHED }
        assertEquals(listOf("confirmed","assumed","confirmed"),publications.map { it.new.decision.status })
        assertEquals(2,RoadDecisionFlight.recorder.snapshot().decisionChanges())
        assertEquals("Awaiting fresh road match",publications[1].new.decision.reason)
    }

    @Test fun delayedResultCannotOverwriteNewerFixAndRejectionIsRecorded() {
        val t=SystemClock.elapsedRealtime();DriveBus.set(state(t+100))
        DriveBus.set(state(t,20));DriveBus.publishLocationSpeed(0.0,state(t).fix!!)
        assertEquals(t+100,DriveBus.state.value.fix!!.elapsedMs)
        assertEquals(30,DriveBus.state.value.limitMph)
        val history=RoadDecisionFlight.recorder.snapshot()
        assertEquals(2,history.events.count { it.stage==FlightStage.REJECTED })
        assertEquals(30,history.current!!.decision.mph)
    }

    @Test fun freezeSurvivesActivityRestorationAndUnfreezeShowsLatestDecision() {
        val t=SystemClock.elapsedRealtime()
        var visible by mutableStateOf(state(t))
        DriveBus.set(visible)
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { DiagnosticsScreen(visible,{_,_->},{}) } }
        compose.onNodeWithText("Freeze diagnostics").performScrollTo().performClick()
        compose.runOnIdle { visible=state(t+1,20);DriveBus.set(visible) }
        compose.onAllNodesWithText("30 mph · confirmed")[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("20 mph · confirmed").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Unfreeze diagnostics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unfreeze diagnostics").performClick()
        compose.onAllNodesWithText("20 mph · confirmed")[0].performScrollTo().assertIsDisplayed()
        assertEquals(20,DriveBus.state.value.limitMph)
    }

    @Test fun uiRecompositionAndHistoryInspectionDoNotCreateRecorderEvents() {
        val t=SystemClock.elapsedRealtime();var visible by mutableStateOf(state(t));DriveBus.set(visible)
        compose.setContent { MaterialTheme { DiagnosticsScreen(visible,{_,_->},{}) } }
        val count=RoadDecisionFlight.recorder.snapshot().events.size
        repeat(3) { compose.runOnIdle { visible=visible.copy(publicCameraCount=it+1) } }
        compose.onNodeWithText("View recent changes").performScrollTo().performClick()
        compose.onNodeWithText("Recent road decision changes").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(count,RoadDecisionFlight.recorder.snapshot().events.size)
    }

    @Test fun clearHistoryDoesNotAlterDrivingCameraOrAudioEligibility() {
        val t=SystemClock.elapsedRealtime();val camera=Camera("user-test",p,CameraType.SPEED,CameraSource.USER)
        val live=state(t).copy(alert=Alert(camera,10.0),decision=CameraDecision(camera,10.0,true,"Approaching"))
        DriveBus.set(live)
        compose.setContent { MaterialTheme { DiagnosticsScreen(live,{_,_->},{}) } }
        compose.onNodeWithText("Freeze diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Clear diagnostic history").performScrollTo().performClick()
        assertEquals(live,DriveBus.state.value);assertTrue(RoadDecisionFlight.recorder.snapshot().events.isEmpty())
        assertNull(DiagnosticsInspection.state.value)
        assertTrue(PendingDrivingEvidence.limitSpeechRelevant(DriveBus.state.value,30,t))
        assertEquals(30,PendingDrivingEvidence.cameraLimit(DriveBus.state.value,t))
    }

    @Test fun frozenCorrectionsCannotActOnAnotherLiveRoad() {
        val t=SystemClock.elapsedRealtime();val live=state(t).let { it.copy(fix=it.fix!!.copy(bearing=0.0)) }
        DriveBus.set(live)
        compose.setContent { MaterialTheme { DiagnosticsScreen(live,{_,_->fail("Frozen correction must not execute")},{}) } }
        compose.onNodeWithText("Reset road").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Freeze diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Reset road").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reset all learned limits & boundaries").performScrollTo().assertIsNotEnabled()
    }

    @Test fun actualOnDeviceExportIsRedactedAndDoesNotModifyDrivingState() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val t=SystemClock.elapsedRealtime();val live=state(t)
        DriveBus.set(live)
        val file=writeRoadDiagnosticReport(context,RoadDecisionFlight.recorder.snapshot())
        try {
            val report=file.readText()
            assertEquals(context.cacheDir.resolve("diagnostic-reports"),file.parentFile)
            assertTrue(report.contains("road-"))
            for(secret in listOf("53.512345678","-2.812345678","way/diagnostic-test","accessToken","Authorization")) assertFalse(secret,report.contains(secret))
            assertEquals(live,DriveBus.state.value)
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
            assertEquals("content",uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use { assertEquals(report,it.bufferedReader().readText()) }
        } finally { file.delete() }
    }

    @Test fun concurrentBusPublicationsKeepHistoryConsistentWithNewestFix() {
        val t=SystemClock.elapsedRealtime()
        val workers=(1..40).map { i -> Thread { DriveBus.set(state(t+i,if(i%2==0) 30 else 20)) } }
        workers.forEach { it.start() };workers.forEach { it.join() }
        val history=RoadDecisionFlight.recorder.snapshot()
        assertEquals(40,history.events.size)
        assertEquals(t+40,DriveBus.state.value.fix!!.elapsedMs)
        assertEquals(t+40,history.current!!.fixId)
        assertEquals(DriveBus.state.value.limitMph,history.current!!.displayedMph)
        assertEquals((1L..40L).toList(),history.events.map { it.sequence-history.events.first().sequence+1 })
    }

    @Test fun freezeReadsCurrentBusRatherThanLaggingRenderedSnapshot() {
        val t=SystemClock.elapsedRealtime();DriveBus.set(state(t))
        // Rendered state may lag a source publication; freeze must capture both latest sources together.
        val rendered=state(t)
        DriveBus.set(state(t+1,20))
        compose.setContent { MaterialTheme { DiagnosticsScreen(rendered,{_,_->},{}) } }
        compose.onNodeWithText("Freeze diagnostics").performScrollTo().performClick()
        val frozen=DiagnosticsInspection.state.value!!
        assertEquals(t+1,frozen.state.fix!!.elapsedMs)
        assertEquals(t+1,frozen.history.current!!.fixId)
        assertEquals(20,frozen.state.limitMph)
    }
}
