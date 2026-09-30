package uk.co.traynor.speedbuddy

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class JunctionUiTest {
    @get:Rule val compose=createComposeRule()
    private val group=CameraJunction("junction:ui-test","Fiveways",GeoPoint(53.0,-2.0),5)

    @Test fun junctionEditorRetainsNameAndRoadCountAfterRecreation() {
        var saved: CameraJunction?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { JunctionEditor(group.copy(name=""),{}) { saved=it } } }
        compose.onNodeWithText("Save junction").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Junction name").performScrollTo().performTextInput("Fourways")
        compose.onNodeWithText("4-way junction").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("4-way junction").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Save junction").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Fourways",saved?.name);assertEquals(4,saved?.ways) }
    }

    @Test fun shortLandscapeJunctionDetailsCanAddLinkAndUngroupWithoutDeleting() {
        var added=false;var linked: Camera?=null;var ungrouped=false
        val camera=Camera("user:nearby",group.point,CameraType.COMBINED,CameraSource.USER,90.0,30,note="East approach")
        compose.setContent {
            val landscape=Configuration(LocalConfiguration.current).apply { screenHeightDp=360;screenWidthDp=720;orientation=Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscape) { MaterialTheme {
                JunctionDetails(group,emptyList(),listOf(camera),{},{},{added=true},{},{linked=it},{ungrouped=true})
            } }
        }
        compose.onNodeWithText("Add camera").performScrollTo().performClick()
        compose.onNodeWithText("Link an existing nearby camera").performScrollTo().performClick()
        compose.onNodeWithText("East approach").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(added);assertEquals(camera.id,linked?.id);assertFalse(ungrouped) }
        compose.onNodeWithText("Ungroup").performScrollTo().performClick()
        compose.onNodeWithText("The junction marker will be removed. Its cameras stay saved and warn individually.").assertIsDisplayed()
        compose.onNodeWithText("Keep cameras and ungroup").performClick()
        compose.runOnIdle { assertTrue(ungrouped) }
    }

    @Test fun groupedCameraEditorRequiresDirectionAndLocalPosition() {
        val camera=Camera("user:member",group.point,CameraType.SPEED,CameraSource.USER,null,30,junction=group)
        compose.setContent { MaterialTheme { CameraEditor(camera,null,false,{}) { _,_,_,_,_,_-> } } }
        compose.onNodeWithText("Save camera").assertIsNotEnabled()
        compose.onNodeWithText("Direction unknown").assertDoesNotExist()
        compose.onNodeWithText("Average speed").assertDoesNotExist()
        compose.onNodeWithText("Advanced bearing entry").performScrollTo().performClick()
        compose.onNodeWithText("Travel direction · 0–359°").performScrollTo().performTextInput("90")
        compose.onNodeWithText("Save camera").assertIsEnabled()
        compose.onNodeWithText("Latitude").performScrollTo().performTextReplacement("54.000000")
        compose.onNodeWithText("Save camera").assertIsNotEnabled()
    }
}
