package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val road = Road("way/1", "Test road", listOf(GeoPoint(53.0, -2.0), GeoPoint(53.01, -2.0)), mapOf("maxspeed" to "30 mph"))
    private fun fix(lat: Double, heading: Double = 0.0, ms: Long = 1000, speed: Double = 12.5) =
        Fix(GeoPoint(lat, -2.0), 5.0, speed, 1.0, heading, ms)

    @Test fun speedConversionAndUnknown() {
        assertEquals(22.369, 10 * MPS_TO_MPH, .001)
        val filter = SpeedFilter()
        assertNull(filter.update(fix(53.0).copy(speedMps = null), 1000))
        assertEquals(27.96, filter.update(fix(53.0), 1000)!!, .1)
        assertNull(filter.current(7001))
        assertNull(filter.update(fix(53.0, ms = 1000), 7001))
    }
    @Test fun speedJitterAndSpikes() {
        val filter = SpeedFilter()
        filter.update(fix(53.0, speed = .6), 1000)
        assertEquals(0.0, filter.update(fix(53.0, ms = 2000, speed = .8), 2000)!!, 0.0)
        val speed = filter.update(fix(53.0, ms = 3000, speed = 13.0), 3000)!!
        assertTrue(speed > 18 && speed < 30)
        assertEquals(speed, filter.update(fix(53.0, ms = 3100, speed = 60.0), 3100)!!, 0.0)
    }
    @Test fun osmSpeedLimitParsing() {
        assertEquals(30, SpeedLimits.mph(mapOf("maxspeed" to "30 mph")))
        assertEquals(50, SpeedLimits.mph(mapOf("maxspeed" to "80")))
        assertEquals(60, SpeedLimits.mph(mapOf("maxspeed" to "GB:nsl_single")))
        assertNull(SpeedLimits.mph(mapOf("maxspeed" to "signals")))
        assertNull(SpeedLimits.mph(mapOf("maxspeed" to "30 mph", "maxspeed:conditional" to "20 @ school")))
        assertNull(OsmSpeedLimitProvider().limit(null))
    }
    @Test fun matchingStaysStableAndRefusesAmbiguity() {
        val other = road.copy(id = "way/2", points = road.points.map { it.copy(lon = it.lon + .0003) })
        val matcher = RoadMatcher()
        assertNull(matcher.match(fix(53.005).copy(point = GeoPoint(53.005, -1.99985)), listOf(road, other)))
        assertEquals("way/1", matcher.match(fix(53.005), listOf(road, other))?.road?.id)
        assertEquals("way/1", matcher.match(fix(53.005).copy(point = GeoPoint(53.005, -1.99981)), listOf(road, other))?.road?.id)
        assertNull(matcher.match(fix(53.005).copy(accuracyM = 60.0), listOf(road)))
    }
    @Test fun simulatedNorthboundCameraCountsDownAndPasses() {
        val camera = Camera("user-1", GeoPoint(53.005, -2.0), CameraType.SPEED, CameraSource.USER)
        val detector = CameraApproachDetector()
        val match = RoadMatch(road, 0.0, 0.0, .9)
        val distances = listOf(53.0005, 53.0023, 53.0041).map { latitude ->
            val (alert, decision) = detector.evaluate(fix(latitude), match, listOf(camera), 28.0)
            assertTrue(decision.accepted); assertEquals(CameraType.SPEED, alert?.camera?.type)
            alert!!.distanceM
        }
        assertTrue(distances[0] > distances[1]); assertTrue(distances[1] > distances[2])
        assertTrue(distances[0] in 450.0..550.0)
        assertNull(detector.evaluate(fix(53.0051), match, listOf(camera), 28.0).first)
        assertNull(detector.evaluate(fix(53.0060), match, listOf(camera), 28.0).first)
    }
    @Test fun oppositeAndParallelCamerasAreRejected() {
        val detector = CameraApproachDetector(); val match = RoadMatch(road, 0.0, 0.0, .9)
        val camera = Camera("opposite", GeoPoint(53.005, -2.0), CameraType.RED_LIGHT, CameraSource.USER, 180.0)
        assertEquals("Opposite enforced direction", detector.evaluate(fix(53.002), match, listOf(camera), 28.0).second.reason)
        val parallel = camera.copy(id = "parallel", direction = null, point = camera.point.copy(lon = -1.9994))
        assertEquals("Different road", detector.evaluate(fix(53.002), match, listOf(parallel), 28.0).second.reason)
        assertEquals("Camera behind or off heading", detector.evaluate(fix(53.006), match, listOf(camera.copy(direction = null)), 28.0).second.reason)
    }
    @Test fun redLightAndDuplicateAlerts() {
        val camera = Camera("red", GeoPoint(53.005, -2.0), CameraType.RED_LIGHT, CameraSource.USER)
        val detector = CameraApproachDetector(); val match = RoadMatch(road, 0.0, 0.0, .9)
        assertEquals(CameraType.RED_LIGHT, detector.evaluate(fix(53.002), match, listOf(camera), 28.0).first?.camera?.type)
        assertEquals("Approach active", detector.evaluate(fix(53.003), match, listOf(camera), 28.0).second.reason)
        detector.evaluate(fix(53.0051), match, listOf(camera), 28.0)
        assertEquals("Already passed", detector.evaluate(fix(53.0049), match, listOf(camera), 28.0).second.reason)
    }
    @Test fun overspeedGateRearms() {
        val gate = OverspeedGate()
        assertFalse(gate.update(30.0, 30, 2)); assertFalse(gate.update(33.0, null, 2))
        assertTrue(gate.update(33.0, 30, 2)); assertFalse(gate.update(34.0, 30, 2))
        assertFalse(gate.update(30.0, 30, 2)); assertTrue(gate.update(33.0, 30, 2))
    }
    @Test fun geometryDistancesAndDirections() {
        assertEquals(500.0, Geo.distance(GeoPoint(53.0, -2.0), GeoPoint(53.0045, -2.0)), 5.0)
        assertEquals(0.0, Geo.bearing(GeoPoint(53.0, -2.0), GeoPoint(53.0045, -2.0)), .01)
        assertEquals(180.0, Geo.difference(0.0, 180.0), .01)
    }
}
