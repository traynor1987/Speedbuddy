package uk.co.traynor.speedbuddy

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DrivingUiTest {
    @get:Rule val compose=createComposeRule()
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
    @Test fun cameraEditorRetainsOwnerInputAfterRecreation() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { CameraEditor(null,GeoPoint(53.0,-2.0),false,{}) { _,_,_,_,_,_-> } } }
        compose.onNodeWithText("Enforced speed · mph").performScrollTo().performTextInput("20")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("20",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Save camera").assertIsDisplayed()
    }
}
