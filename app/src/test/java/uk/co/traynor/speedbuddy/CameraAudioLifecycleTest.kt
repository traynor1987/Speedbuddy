package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class CameraAudioLifecycleTest {
    private val camera = Camera("camera", GeoPoint(53.0, -2.0), CameraType.SPEED, CameraSource.USER, enforcedMph = 30)

    @Test fun queuedSpeechExpiresAndRechecksCurrentRelevanceAndVoice() {
        var relevant = true
        var voice = true
        val queued = QueuedCameraSpeech("warning", 1_000L, true, { relevant }, { voice })
        assertTrue(queued.playableAt(1_500L))
        assertFalse(queued.playableAt(11_001L))
        assertFalse(queued.playableAt(999L))
        relevant = false; assertFalse(queued.playableAt(1_500L))
        relevant = true; voice = false; assertFalse(queued.playableAt(1_500L))
    }

    @Test fun speedingSpeechRequiresSameFreshEnabledApproachAndCurrentSpeed() {
        fun valid(alert: Alert? = Alert(camera, 180.0), speed: Double? = 35.0,
            fresh: Boolean = true, enabled: Boolean = true) = CameraCueValidity.relevant(
                alert, speed, fresh, enabled, camera.id, true, 30, 2, 1_000L)
        assertTrue(valid())
        assertFalse(valid(speed = 31.0)); assertFalse(valid(speed = null))
        assertFalse(valid(fresh = false)); assertFalse(valid(enabled = false))
        assertFalse(valid(alert = null))
        assertFalse(valid(alert = Alert(camera.copy(id = "other"), 180.0)))
        assertFalse(valid(alert = Alert(camera, 500.0)))
        val expired = MobileReport("mobile:test", camera.point, 1L, 1L, 7_200_001L, 120, null, null, null).asCamera()
        assertFalse(CameraCueValidity.relevant(Alert(expired, 100.0), 35.0, true, true, expired.id, true, 30, 2, 7_200_001L))
    }

    @Test fun limitChangeWaitsUntilCameraAudioFinishesAndLatestLimitWins() {
        val gate = DeferredLimitVoice()
        assertNull(gate.update(30, false, true))
        assertNull(gate.update(20, true, true))
        assertEquals(20, gate.update(20, false, true))
        assertNull(gate.update(20, false, true))
        assertNull(gate.update(40, true, true))
        assertNull(gate.update(30, true, true))
        assertEquals(30, gate.update(30, false, true))
    }

    @Test fun queuedSpeedingLimitMustStillBeTheCurrentReliableLimit() {
        fun valid(currentLimit: Int?) = CameraCueValidity.relevant(Alert(camera, 180.0),
            35.0, true, true, camera.id, true, 30, 2, 1_000L, currentLimit)
        assertTrue(valid(30))
        assertFalse(valid(40)); assertFalse(valid(20)); assertFalse(valid(null))
    }

    @Test fun unknownOrDisabledLimitDoesNotReplayDeferredAnnouncement() {
        val gate = DeferredLimitVoice()
        gate.update(30, false, true); gate.update(20, true, true)
        assertNull(gate.update(null, false, true))
        assertNull(gate.update(20, false, true))
        gate.update(40, true, true)
        assertNull(gate.update(40, false, false))
        assertNull(gate.update(40, false, true))
    }
}
