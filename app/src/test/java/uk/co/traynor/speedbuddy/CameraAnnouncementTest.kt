package uk.co.traynor.speedbuddy

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAnnouncementTest {
    private fun camera(type: CameraType, enforcedMph: Int? = null) = Camera(
        "camera", GeoPoint(53.0, -2.0), type, CameraSource.USER, enforcedMph = enforcedMph)

    @Test fun cameraLimitTakesPriorityOverRoadLimit() {
        assertEquals("Speed camera ahead. Speed limit 20 miles per hour.",
            CameraAnnouncement.text(camera(CameraType.SPEED, 20), 30))
    }

    @Test fun matchedRoadLimitUsedOnlyWhenCameraLimitMissing() {
        assertEquals("Average speed camera ahead. Speed limit 40 miles per hour.",
            CameraAnnouncement.text(camera(CameraType.AVERAGE), 40))
        assertEquals("Speed camera ahead.", CameraAnnouncement.text(camera(CameraType.SPEED), null))
    }

    @Test fun cameraTypeIsSpokenClearly() {
        assertEquals("Red light camera ahead.", CameraAnnouncement.text(camera(CameraType.RED_LIGHT), null))
        assertEquals("Red light and speed camera ahead. Speed limit 30 miles per hour.",
            CameraAnnouncement.text(camera(CameraType.COMBINED, 30), null))
    }
}
