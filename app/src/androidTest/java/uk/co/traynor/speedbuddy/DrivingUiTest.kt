package uk.co.traynor.speedbuddy

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DrivingUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun unknownUsesQuestionSignAndChangingHasNoUnverifiedNumber() {
        var state by mutableStateOf(DriveState())
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        // The correction target intentionally clears child semantics. Check its
        // rendered sign, rather than querying a glyph hidden from accessibility.
        val pixels=compose.onNodeWithTag("current-road-limit").performScrollTo().assertIsDisplayed().captureToImage().toPixelMap()
        val ring=pixels[(pixels.width*.04).toInt(),pixels.height/2]
        val face=pixels[(pixels.width*.20).toInt(),pixels.height/2]
        assertTrue(ring.red>.7f && ring.green<.3f && ring.blue<.3f)
        assertTrue(face.red>.95f && face.green>.95f && face.blue>.95f)
        assertTrue((pixels.width/3 until pixels.width*2/3).any { x ->
            (pixels.height/4 until pixels.height*3/4).any { y -> pixels[x,y].let { it.red<.15f && it.green<.15f && it.blue<.15f } }
        })
        compose.onNodeWithText("Limit unknown").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state=state.copy(limitDecision=LimitDecision(null,reason="Resolving junction",changing=true)) }
        compose.onNodeWithText("Limit changing…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("?").assertDoesNotExist()
        compose.onNodeWithText("Limit unknown").assertDoesNotExist()
    }
    @Test fun assumedBadgeAndRedLabelDisappearImmediatelyForNewConfirmedLimit() {
        var state by mutableStateOf(DriveState(limitMph=20,limitDecision=LimitDecision(20,reason="Gap",assumed=true)))
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithText("!").assertIsDisplayed()
        compose.onNodeWithText("⚠ ASSUMED").assertIsDisplayed()
        compose.runOnIdle { state=state.copy(limitMph=30,limitDecision=LimitDecision(30,reason="Verified")) }
        compose.onNodeWithText("!").assertDoesNotExist()
        compose.onNodeWithText("⚠ ASSUMED").assertDoesNotExist()
        compose.onNodeWithText("30 mph").assertIsDisplayed()
    }
    @Test fun unknownSupportingTextDoesNotToggleWithPendingCorrectionReadiness() {
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/status",null,listOf(p,Geo.ahead(p,0.0,100.0)),emptyMap())
        val fix=Fix(p,5.0,0.0,1.0,null,1000)
        var state by mutableStateOf(DriveState(active=true,fix=fix))
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        val text="Waiting to verify this road"
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        repeat(3) {
            compose.runOnIdle { state=state.copy(road=RoadMatch(road,0.0,null,1.0),roadDecisionElapsedMs=1000) }
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { state=state.copy(roadDecisionElapsedMs=999) }
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state=state.copy(limitMph=30,limitDecision=LimitDecision(30,reason="Confirmed")) }
        compose.onNodeWithText(text).assertDoesNotExist()
    }
    @Test fun sameNumberConfirmationRemovesAssumedBadgeImmediately() {
        var state by mutableStateOf(DriveState(limitMph=30,limitDecision=LimitDecision(30,reason="Gap",assumed=true)))
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithText("⚠ ASSUMED").assertIsDisplayed()
        compose.runOnIdle { state=state.copy(limitDecision=LimitDecision(30,reason="Confirmed")) }
        compose.onNodeWithText("!").assertDoesNotExist()
        compose.onNodeWithText("⚠ ASSUMED").assertDoesNotExist()
        compose.onNodeWithText("30 mph").assertIsDisplayed()
    }
    @Test fun mainMapEntryAndNaturalSignCorrectionCoexist() {
        val point=GeoPoint(53.5,-2.8)
        val road=Road("way/ui-coexist","Test road",listOf(point,Geo.ahead(point,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
        val state=DriveState(active=true,limitMph=30,road=RoadMatch(road,0.0,0.0,.95))
        var openedMap=false
        var correction: Triple<String,Int?,String?>?=null
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{openedMap=true},{},{},{},{},
            onFeedback={action,mph,id->correction=Triple(action,mph,id)}) } }
        compose.onNodeWithText("Map").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(openedMap) }
        compose.onNodeWithContentDescription("Correct road speed limit").performClick()
        compose.onNodeWithText("Choose the real limit").assertIsDisplayed()
        compose.onNodeWithContentDescription("40 mph").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("SET_LIMIT",40,"way/ui-coexist"),correction) }
        compose.onNodeWithText("Choose the real limit").assertDoesNotExist()
        compose.onNodeWithText("Map").assertIsDisplayed()
    }
    @Test fun unknownLimitPlusUsesSameMatchedRoadPicker() {
        val point=GeoPoint(53.5,-2.8)
        val road=Road("way/ui-unknown","Test road",listOf(point,Geo.ahead(point,0.0,400.0)),emptyMap())
        val state=DriveState(active=true,road=RoadMatch(road,0.0,0.0,.95))
        var oldEditorOpened=false
        var correction: Triple<String,Int?,String?>?=null
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{oldEditorOpened=true},
            onFeedback={action,mph,id->correction=Triple(action,mph,id)}) } }
        compose.onNodeWithContentDescription("Set this road's speed limit").performClick()
        compose.onNodeWithText("Choose the real limit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unknown").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(oldEditorOpened)
            assertEquals(Triple("SET_LIMIT",OWNER_UNKNOWN,"way/ui-unknown"),correction)
        }
    }
    @Test fun assumedRoadAndBoundaryFeedbackRemainVisibleWithMapEntry() {
        val point=GeoPoint(53.5,-2.8)
        val road=Road("way/ui-boundary","Test road",listOf(point,Geo.ahead(point,0.0,400.0)),emptyMap())
        val state=DriveState(active=true,limitMph=30,sourceLimitMph=40,road=RoadMatch(road,0.0,0.0,.95),
            limitDecision=LimitDecision(30,reason="inherited",assumed=true),roadDataStatus="Saved roads available",
            tooEarlyAvailable=true,boundaryAvailable=true,correctionMessage="Correction saved")
        var correction: Triple<String,Int?,String?>?=null
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{},
            onFeedback={action,mph,id->correction=Triple(action,mph,id)}) } }
        compose.onNodeWithText("Map").assertIsDisplayed()
        compose.onNodeWithText("!").assertIsDisplayed()
        compose.onNodeWithText("⚠ ASSUMED").assertIsDisplayed()
        compose.onNodeWithText("Saved roads available").assertIsDisplayed()
        compose.onNodeWithText("Correction saved").assertIsDisplayed()
        compose.onNodeWithContentDescription("Correct road speed limit").performClick()
        compose.onNodeWithText("Changed too soon").assertDoesNotExist()
        compose.onNodeWithText("This limit starts here").assertDoesNotExist()
        compose.onNodeWithContentDescription("60 mph").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("SET_LIMIT",60,"way/ui-boundary"),correction) }
        compose.onNodeWithContentDescription("Correct road speed limit").performClick()
        compose.onNodeWithContentDescription("40 mph").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("SET_LIMIT",40,"way/ui-boundary"),correction) }
    }
    @Test fun unknownWithoutCurrentRoadExplainsThatItMustFirstBeIdentified() {
        val elapsed=android.os.SystemClock.elapsedRealtime()
        val state=DriveState(active=true,fix=Fix(GeoPoint(53.5,-2.8),8.0,0.0,null,0.0,elapsed))
        var correction: Triple<String,Int?,String?>?=null
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{},
            onFeedback={action,mph,id->correction=Triple(action,mph,id)}) } }
        compose.onNodeWithContentDescription("Set this road's speed limit").performClick()
        compose.onNodeWithText("Waiting to identify this road — + becomes available when ready").assertIsDisplayed()
        compose.onNodeWithText("Choose the real limit").assertDoesNotExist()
        compose.runOnIdle { assertNull(correction) }
    }
    @Test fun retainedSpeechGeometryCannotBeUsedAsNewGpsCorrectionTarget() {
        val elapsed=android.os.SystemClock.elapsedRealtime()
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/pending",null,listOf(p,Geo.ahead(p,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
        val state=DriveState(active=true,limitMph=30,road=RoadMatch(road,0.0,0.0,.95),
            fix=Fix(p,5.0,10.0,1.0,0.0,elapsed),roadDecisionElapsedMs=elapsed-1000,
            limitDecision=LimitDecision(30,reason=PendingDrivingEvidence.reason,assumed=true))
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithContentDescription("Correct road speed limit").performClick()
        compose.onNodeWithText("Choose the real limit").assertDoesNotExist()
    }
    @Test fun mobileWarningShowsMinimalActionsAndKeepsMainSignCentred() {
        var reported=false;var feedback: Boolean?=null
        val now=System.currentTimeMillis();val elapsed=android.os.SystemClock.elapsedRealtime()
        val report=MobileReport("mobile:ui",GeoPoint(53.0,-2.0),now,now,now+7_200_000,120,0.0,"way/1","High Street")
        val state=DriveState(active=true,limitMph=30,speedMph=0.0,
            fix=Fix(report.point,4.0,0.0,null,null,elapsed),alert=Alert(report.asCamera(),200.0))
        compose.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{},
            onReportMobile={reported=true},onMobileFeedback={_,confirmed->feedback=confirmed}) } }
        compose.onNodeWithText("MOBILE CAMERA REPORTED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Still there").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true,feedback) }
        compose.onNodeWithText("Not there").performClick()
        compose.runOnIdle { assertEquals(false,feedback) }
        compose.onNodeWithText("Report mobile camera").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(reported) }
    }
    @Test fun mainSignStaysCentredWithEitherTurnAndWithoutTurns() {
        var drive by mutableStateOf(DriveState(limitMph=30))
        compose.setContent { MaterialTheme { DriveScreen(drive,{},{},{},{},{},{},{},{}) } }
        val original=compose.onNodeWithTag("current-road-limit").fetchSemanticsNode().boundsInRoot
        for(direction in listOf(TurnDirection.LEFT,TurnDirection.RIGHT)) {
            compose.runOnIdle { drive=drive.copy(turns=listOf(TurnLimit(20,100.0,direction,false))) }
            val changed=compose.onNodeWithTag("current-road-limit").fetchSemanticsNode().boundsInRoot
            assertEquals(original.center.x,changed.center.x,.1f)
            assertEquals(original.width,changed.width,.1f)
        }
    }
    @Test fun shortLandscapeCanScrollToDrivingControls() {
        compose.setContent {
            val landscape=Configuration(LocalConfiguration.current).apply { screenHeightDp=360;screenWidthDp=720;orientation=Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscape) { MaterialTheme { DriveScreen(DriveState(),{},{},{},{},{},{},{},{}) } }
        }
        compose.onNode(hasText("Start driving mode") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }
    @Test fun correctionPickerRetainsCurrentRoadAfterRecreation() {
        val restoration=StateRestorationTester(compose)
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/restore",null,listOf(p,Geo.ahead(p,0.0,400.0)),emptyMap())
        var selected: String?=null
        restoration.setContent { MaterialTheme { DriveScreen(DriveState(active=true,road=RoadMatch(road,0.0,0.0,.95),fix=Fix(p,5.0,0.0,null,null,1000),roadDecisionElapsedMs=1000),{},{},{},{},{},{},{},{},
            onFeedback={_,_,id->selected=id}) } }
        compose.onNodeWithContentDescription("Set this road's speed limit").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Choose the real limit").assertIsDisplayed()
        compose.onNodeWithContentDescription("40 mph").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("way/restore",selected) }
    }
    @Test fun restoredPickerCannotCorrectAChangedRoad() {
        val restoration=StateRestorationTester(compose)
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/first",null,listOf(p,Geo.ahead(p,0.0,400.0)),emptyMap())
        var state by mutableStateOf(DriveState(active=true,road=RoadMatch(road,0.0,0.0,.95),fix=Fix(p,5.0,0.0,null,null,1000),roadDecisionElapsedMs=1000))
        restoration.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithContentDescription("Set this road's speed limit").performClick()
        compose.runOnIdle { state=state.copy(road=state.road!!.copy(road=road.copy(id="way/second"))) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Choose the real limit").assertDoesNotExist()
    }
    @Test fun restoredPickerWaitsForNewGpsFrameToBeMatched() {
        val restoration=StateRestorationTester(compose)
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/pending-restored",null,listOf(p,Geo.ahead(p,0.0,400.0)),emptyMap())
        var state by mutableStateOf(DriveState(active=true,road=RoadMatch(road,0.0,0.0,.95),
            fix=Fix(p,5.0,0.0,null,null,1000),roadDecisionElapsedMs=1000))
        restoration.setContent { MaterialTheme { DriveScreen(state,{},{},{},{},{},{},{},{}) } }
        compose.onNodeWithContentDescription("Set this road's speed limit").performClick()
        compose.runOnIdle { state=state.copy(fix=state.fix!!.copy(elapsedMs=2000)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Choose the real limit").assertDoesNotExist()
    }
    @Test fun tallLargeTextLayoutCanReachDrivingControls() {
        compose.setContent {
            val config=Configuration(LocalConfiguration.current).apply { screenHeightDp=900;screenWidthDp=600;fontScale=2f }
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)) {
                MaterialTheme { DriveScreen(DriveState(),{},{},{},{},{},{},{},{}) }
            }
        }
        compose.onNode(hasText("Start driving mode") and hasClickAction()).performScrollTo().assertIsDisplayed()
    }
    @Test fun cameraEditorRetainsOwnerInputAfterRecreation() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { CameraEditor(null,GeoPoint(53.0,-2.0),false,{}) { _,_,_,_,_,_-> } } }
        compose.onNodeWithText("Enforced speed · mph").performScrollTo().performTextInput("20")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("20",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Save camera").assertIsDisplayed()
    }
}
