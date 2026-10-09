package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class CameraWarningTest {
    private val point = GeoPoint(53.0, -2.0)
    private val camera = Camera("stages", point, CameraType.SPEED, CameraSource.USER, enforcedMph = 30)
    private val road = Road("way/1", "High Street",
        listOf(Geo.ahead(point, 180.0, 600.0), Geo.ahead(point, 0.0, 600.0)), mapOf("maxspeed" to "30 mph"))
    private val match = RoadMatch(road, 0.0, 0.0, .9)
    private fun fix(distance: Double, accuracy: Double = 4.0, bearing: Double? = 0.0) =
        Fix(Geo.ahead(point, 180.0, distance), accuracy, 12.0, 1.0, bearing, 1_000L)
    private fun evaluate(detector: CameraApproachDetector, distance: Double, speed: Double? = 28.0,
        target: Camera = camera, accuracy: Double = 4.0, bearing: Double? = 0.0,
        roadMatch: RoadMatch? = match, limit: Int? = 30, tolerance: Int = 2) =
        detector.evaluate(fix(distance, accuracy, bearing), roadMatch, listOf(target), speed,
            matchedRoadLimitMph = limit, toleranceMph = tolerance).first

    @Test fun normalApproachThenDoubleBeepOnlyAt100Yards() {
        val detector = CameraApproachDetector()
        assertNull(evaluate(detector, 280.0))
        val first = evaluate(detector, 270.0)!!.warning!!
        assertTrue(first.announceApproach); assertFalse(first.doubleBeep)
        assertNull(evaluate(detector, 100.0)!!.warning)
        val close = evaluate(detector, 91.0)!!.warning!!
        assertTrue(close.closeReminder); assertTrue(close.doubleBeep); assertFalse(close.announceApproach)
        assertNull(evaluate(detector, 85.0)!!.warning)
        assertNull(evaluate(detector, 95.0)!!.warning)
        assertNull(evaluate(detector, 90.0)!!.warning)
    }

    @Test fun speedingAtEntryCoalescesWithUsualAnnouncementAndDoesNotRepeat() {
        val detector = CameraApproachDetector()
        val cue = evaluate(detector, 250.0, 35.0)!!.warning!!
        assertTrue(cue.announceApproach); assertTrue(cue.speeding); assertTrue(cue.doubleBeep)
        assertEquals(30, cue.limitMph)
        assertNull(evaluate(detector, 230.0, 36.0)!!.warning)
        val close = evaluate(detector, 90.0, 35.0)!!.warning!!
        assertTrue(close.closeReminder); assertFalse(close.speeding)
    }

    @Test fun accelerationDuringApproachWarnsOnceEvenAfterSlowingAndSpeedingAgain() {
        val detector = CameraApproachDetector()
        evaluate(detector, 250.0)
        assertTrue(evaluate(detector, 220.0, 34.0)!!.warning!!.speeding)
        assertNull(evaluate(detector, 210.0, 28.0)!!.warning)
        assertNull(evaluate(detector, 200.0, 36.0)!!.warning)
    }

    @Test fun startingInside100YardsCombinesAllCuesInOneWarning() {
        val cue = evaluate(CameraApproachDetector(), 85.0, 35.0)!!.warning!!
        assertTrue(cue.announceApproach); assertTrue(cue.closeReminder); assertTrue(cue.speeding)
    }

    @Test fun cameraLimitWinsAndToleranceBoundaryIsStrict() {
        val target = camera.copy(enforcedMph = 20)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 22.0, target)!!.warning!!.speeding)
        val cue = evaluate(CameraApproachDetector(), 250.0, 22.1, target)!!.warning!!
        assertTrue(cue.speeding); assertEquals(20, cue.limitMph)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 24.0, target, tolerance = 5)!!.warning!!.speeding)
    }

    @Test fun missingWeakOrDifferentRoadLimitNeverInventsSpeeding() {
        val target = camera.copy(enforcedMph = null)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 45.0, target, limit = null)!!.warning!!.speeding)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 45.0, target, roadMatch = match.copy(confidence = .3))!!.warning!!.speeding)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 45.0, target, roadMatch = null)!!.warning!!.speeding)
        assertFalse(evaluate(CameraApproachDetector(), 250.0, 45.0, target.copy(point = Geo.ahead(point, 90.0, 25.0)))!!.warning!!.speeding)
        assertTrue(evaluate(CameraApproachDetector(), 250.0, 45.0, target)!!.warning!!.speeding)
    }

    @Test fun stoppedAndWeakGpsKeepCardWithoutEmittingOrConsumingCloseCue() {
        val detector = CameraApproachDetector()
        evaluate(detector, 200.0)
        assertNull(evaluate(detector, 90.0, 0.0, bearing = null)!!.warning)
        assertNull(evaluate(detector, 90.0, 35.0, accuracy = 45.0)!!.warning)
        assertTrue(evaluate(detector, 90.0)!!.warning!!.closeReminder)
        assertNull(evaluate(detector, 85.0, 0.0, bearing = null)!!.warning)
    }

    @Test fun passedCameraIsSilentAndLeavingEncounterRearmsAllStages() {
        val detector = CameraApproachDetector()
        evaluate(detector, 90.0, 35.0)
        assertNull(evaluate(detector, -10.0, 35.0))
        assertNull(evaluate(detector, 90.0, 35.0))
        evaluate(detector, 900.0)
        val cue = evaluate(detector, 90.0, 35.0)!!.warning!!
        assertTrue(cue.announceApproach); assertTrue(cue.closeReminder); assertTrue(cue.speeding)
    }

    @Test fun mobileReportsUseSameStagesAndCannotWarnAfterExpiry() {
        val report = MobileReport("mobile:stages", point, 1L, 1L, 7_200_001L, 120, 0.0, road.id, road.name)
        val detector = CameraApproachDetector()
        val first = detector.evaluate(fix(250.0), match, listOf(report.asCamera()), 35.0,
            nowMs = 1_000L, matchedRoadLimitMph = 30).first!!.warning!!
        assertTrue(first.speeding)
        assertTrue(detector.evaluate(fix(90.0), match, listOf(report.asCamera()), 28.0,
            nowMs = 2_000L).first!!.warning!!.closeReminder)
        assertNull(detector.evaluate(fix(80.0), match, listOf(report.asCamera()), 35.0, nowMs = 7_200_001L).first)
    }

    @Test fun speedingSpeechPreservesCameraTypeAndKnownLimit() {
        assertEquals("Warning, speeding. Speed camera ahead. Camera limit 30 miles per hour.",
            CameraAnnouncement.text(camera, 40, speeding = true))
        assertEquals("Warning, speeding. Mobile speed camera reported ahead. Camera limit 20 miles per hour.",
            CameraAnnouncement.text(camera.copy(type = CameraType.MOBILE, enforcedMph = null), 20, speeding = true))
    }

    @Test fun audioPlanUsesOneDoubleBeepThenOneSpeechForCombinedCue() {
        val plan = CameraAudioCue.from(camera, CameraWarning(true, true, true, 30), true)
        assertTrue(plan.doubleBeep)
        assertEquals("Warning, speeding. Speed camera ahead. Camera limit 30 miles per hour.", plan.speech)
    }

    @Test fun proximityIsBeepOnlyAndVoiceOffKeepsDoubleBeep() {
        val close = CameraAudioCue.from(camera, CameraWarning(false, true, false, 30), true)
        assertTrue(close.doubleBeep); assertNull(close.speech)
        val muted = CameraAudioCue.from(camera, CameraWarning(true, false, true, 30), false)
        assertTrue(muted.doubleBeep); assertNull(muted.speech)
        val entry = CameraAudioCue.from(camera, CameraWarning(true, false, false, 30), true)
        assertFalse(entry.doubleBeep)
        assertEquals("Speed camera ahead. Camera limit 30 miles per hour.", entry.speech)
    }
}
