package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class JunctionCamerasTest {
    private val centre = GeoPoint(53.0, -2.0)
    private val junction = CameraJunction("junction:test", "Fiveways", centre, 5)
    private fun camera(id: String, distance: Double = 0.0, direction: Double = 0.0) =
        Camera(id, Geo.ahead(centre, 0.0, distance), CameraType.COMBINED, CameraSource.USER,
            direction, 30, junction = junction)
    private fun fix(distance: Double, heading: Double? = 0.0, accuracy: Double = 4.0) =
        Fix(Geo.ahead(centre, 180.0, distance), accuracy, 12.0, 1.0, heading, 1_000L)

    @Test fun junctionMetadataRejectsInvalidRoadCountCoordinatesAndNames() {
        JunctionRules.validate(junction); JunctionRules.validate(junction.copy(ways = 4))
        listOf(junction.copy(ways = 3), junction.copy(name = " "), junction.copy(name = "x".repeat(61)),
            junction.copy(id = "not-a-junction"), junction.copy(point = GeoPoint(Double.NaN, -2.0))).forEach {
            assertThrows(IllegalArgumentException::class.java) { JunctionRules.validate(it) }
        }
    }

    @Test fun groupedCameraRequiresFixedTypeKnownDirectionAndNearbyPosition() {
        val valid = camera("one")
        JunctionRules.validateMember(valid, junction)
        listOf(valid.copy(source = CameraSource.OSM), valid.copy(type = CameraType.AVERAGE),
            valid.copy(type = CameraType.MOBILE), valid.copy(direction = null),
            valid.copy(point = Geo.ahead(centre, 0.0, 301.0))).forEach {
            assertThrows(IllegalArgumentException::class.java) { JunctionRules.validateMember(it, junction) }
        }
    }

    @Test fun memberSwitchSharesApproachProximityAndSpeedingStagesButKeepsPerCameraPassing() {
        val first = camera("first")
        val second = camera("second", 40.0)
        val detector = CameraApproachDetector()
        assertTrue(detector.evaluate(fix(240.0), null, listOf(first, second), 35.0).first!!.warning!!.announceApproach)
        val close = detector.evaluate(fix(90.0), null, listOf(first, second), 35.0).first!!.warning!!
        assertTrue(close.closeReminder); assertFalse(close.speeding)
        val afterFirst = detector.evaluate(fix(-10.0), null, listOf(first, second), 35.0).first!!
        assertEquals(second.id, afterFirst.camera.id)
        assertNull(afterFirst.warning)
        assertNull(detector.evaluate(fix(-60.0), null, listOf(first, second), 35.0).first)
        detector.evaluate(fix(900.0), null, listOf(first, second), 28.0)
        val again = detector.evaluate(fix(80.0), null, listOf(first, second), 35.0).first!!.warning!!
        assertTrue(again.announceApproach); assertTrue(again.closeReminder); assertTrue(again.speeding)
    }

    @Test fun rejectedOtherArmsCannotConsumeTheJunctionWarning() {
        val opposite = camera("opposite", direction = 180.0)
        val correct = camera("correct")
        val detector = CameraApproachDetector()
        assertNull(detector.evaluate(fix(200.0), null, listOf(opposite), 28.0).first)
        val alert = detector.evaluate(fix(190.0), null, listOf(opposite, correct), 28.0).first!!
        assertEquals(correct.id, alert.camera.id); assertTrue(alert.warning!!.announceApproach)
    }

    @Test fun groupAndUnrelatedCameraHaveIndependentEncounters() {
        val detector = CameraApproachDetector()
        val member = camera("grouped")
        detector.evaluate(fix(200.0), null, listOf(member), 28.0)
        val standalone = member.copy(id = "ordinary", junction = null)
        assertTrue(detector.evaluate(fix(190.0), null, listOf(standalone), 28.0).first!!.warning!!.announceApproach)
        assertEquals(junction.id, CameraEncounters.key(member))
        assertEquals(standalone.id, CameraEncounters.key(standalone))
    }

    @Test fun speechNamesTheJunctionAndKeepsTypeAndEnforcedLimit() {
        assertEquals("Warning, speeding. Red light and speed camera ahead at Fiveways junction. Speed limit 30 miles per hour.",
            CameraAnnouncement.text(camera("first"),30,true))
        val named=camera("second").copy(junction=junction.copy(name="Fiveways Junction"))
        assertEquals("Red light and speed camera ahead at Fiveways Junction. Speed limit 30 miles per hour.",
            CameraAnnouncement.text(named,30))
    }

    @Test fun queuedSpeechCanFollowRelevantMemberButCancelsForChangedSpeedLimitOrOtherEncounter() {
        val next=Alert(camera("second"),40.0)
        fun relevant(alert: Alert?,limit: Int?=30)=CameraCueValidity.relevant(alert,40.0,true,true,
            junction.id,true,30,0,1_000,currentLimit=limit)
        assertTrue(relevant(next))
        assertFalse(relevant(next,20))
        assertFalse(relevant(next.copy(camera=next.camera.copy(junction=null))))
        assertFalse(relevant(null))
    }

    @Test fun turningTowardsAnUnannouncedArmDoesNotTreatItAsAlreadyPassed() {
        val north=camera("north",100.0)
        val west=camera("west").copy(point=Geo.ahead(centre,270.0,60.0),direction=270.0)
        val detector=CameraApproachDetector()
        assertEquals(north.id,detector.evaluate(fix(160.0),null,listOf(north,west),28.0).first!!.camera.id)
        detector.evaluate(fix(10.0),null,listOf(north,west),28.0)
        val turned=detector.evaluate(fix(0.0,heading=270.0),null,listOf(north,west),28.0).first!!
        assertEquals(west.id,turned.camera.id)
        assertTrue(turned.warning!!.closeReminder)
        assertFalse(turned.warning!!.announceApproach)
    }
}
