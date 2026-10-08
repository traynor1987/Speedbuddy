package uk.co.traynor.speedbuddy

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the real TTS-error completion path without depending on installed voice data. */
class CameraVoiceRecoveryTest {
    private fun error(relevant: Boolean=true,voiceAllowed: Boolean=true,callbackId: String="current"): Int {
        var beeps=0
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val voice=CameraVoice(instrumentation.targetContext) { beeps++ }
            fun field(name: String,value: Any) {
                CameraVoice::class.java.getDeclaredField(name).apply { isAccessible=true }.set(voice,value)
            }
            field("activeUtterance","current")
            field("activeRelevant",{relevant})
            field("activeVoiceAllowed",{voiceAllowed})
            CameraVoice::class.java.getDeclaredMethod("failUtterance",String::class.java).apply {
                isAccessible=true
            }.invoke(voice,callbackId)
            voice.close()
        }
        return beeps
    }
    @Test fun currentSpeechFailureUsesFallback() { assertEquals(1,error()) }
    @Test fun lostGpsOrDisabledVoiceDoesNotEmitLateFallback() {
        assertEquals(0,error(relevant=false))
        assertEquals(0,error(voiceAllowed=false))
    }
    @Test fun supersededSpeechErrorDoesNotBeepForNewWarning() { assertEquals(0,error(callbackId="old")) }
}
