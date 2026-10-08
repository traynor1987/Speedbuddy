package uk.co.traynor.speedbuddy

import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LimitSignVisualTest {
    @get:Rule val compose=createComposeRule()
    @Test fun questionSignKeepsWhiteFaceRedRingAndBlackGlyphAcrossThemesAndOrientation() {
        var dark by mutableStateOf(false);var landscape by mutableStateOf(false)
        compose.setContent {
            val configuration=Configuration(LocalConfiguration.current).apply { orientation=if(landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                    LimitSign(null,false,Modifier.size(150.dp).testTag("question-sign"))
                }
            }
        }
        for(night in listOf(false,true)) for(wide in listOf(false,true)) {
            compose.runOnIdle { dark=night;landscape=wide }
            compose.onNodeWithText("?").assertIsDisplayed()
            val image=compose.onNodeWithTag("question-sign").captureToImage().toPixelMap()
            val ring=image[(image.width*.04).toInt(),image.height/2]
            val face=image[(image.width*.20).toInt(),image.height/2]
            assertTrue(ring.red>.7f && ring.green<.3f && ring.blue<.3f)
            assertTrue(face.red>.95f && face.green>.95f && face.blue>.95f)
            assertTrue((image.width/3 until image.width*2/3).any { x ->
                (image.height/4 until image.height*3/4).any { y -> image[x,y].let { it.red<.15f && it.green<.15f && it.blue<.15f } }
            })
        }
    }
}
