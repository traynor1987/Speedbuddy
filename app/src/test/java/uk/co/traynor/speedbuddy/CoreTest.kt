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
        assertEquals(70, SpeedLimits.mph(mapOf("maxspeed:type" to "GB:motorway")))
        assertEquals(30, SpeedLimits.mph(mapOf("source:maxspeed" to "GB:nsl_restricted")))
        assertNull(SpeedLimits.mph(mapOf("highway" to "motorway")))
        assertNull(SpeedLimits.mph(mapOf("maxspeed:type" to "GB:motorway", "maxspeed:variable" to "yes")))
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
        assertNull(detector.evaluate(fix(53.0005), match, listOf(camera), 28.0).first)
        val distances = listOf(53.0028, 53.0035, 53.0041).map { latitude ->
            val (alert, decision) = detector.evaluate(fix(latitude), match, listOf(camera), 28.0)
            assertTrue(decision.accepted); assertEquals(CameraType.SPEED, alert?.camera?.type)
            alert!!.distanceM
        }
        assertTrue(distances[0] > distances[1]); assertTrue(distances[1] > distances[2])
        assertTrue(distances[0] in 230.0..260.0)
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
        assertEquals(camera.id, CameraApproachDetector().evaluate(fix(53.003), match,
            listOf(camera.copy(bidirectional = true)), 28.0).first?.camera?.id)
    }
    @Test fun cameraOnFollowingRoadSegmentIsNotDiscarded() {
        val shortRoad = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.002, -2.0)))
        val camera = Camera("next-segment", GeoPoint(53.005, -2.0), CameraType.SPEED, CameraSource.OSM)
        val match = RoadMatch(shortRoad, 0.0, 0.0, .9)
        assertNull(CameraApproachDetector().evaluate(fix(53.001), match, listOf(camera), 28.0).first)
        assertEquals(camera.id, CameraApproachDetector().evaluate(fix(53.003), match, listOf(camera), 28.0).first?.camera?.id)
    }
    @Test fun upcomingLimitRequiresConnectedSameRoadAndKnownDifferentLimit() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val next = road.copy(id = "way/2", points = listOf(GeoPoint(53.003, -2.0), GeoPoint(53.008, -2.0)), tags = mapOf("maxspeed" to "40 mph"))
        val match = RoadMatch(current, 0.0, 0.0, .9)
        val result = UpcomingLimitDetector().detect(fix(53.0015), match, 30, listOf(current, next))
        assertEquals(40, result?.mph)
        assertTrue(result!!.distanceM in 150.0..180.0)
        assertNull(UpcomingLimitDetector().detect(fix(53.001), match, 30, listOf(current, next)))
        assertNull(UpcomingLimitDetector().detect(fix(53.001), match, 30, listOf(current, next.copy(tags = emptyMap()))))
        assertNull(UpcomingLimitDetector().detect(fix(53.001), match, 30, listOf(current, next.copy(name = "Side street"))))
    }
    @Test fun junctionPreviewsKnownLeftAndRightChangesWithoutChoosingAPath() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val junction = current.points.last()
        val left = Road("way/left", "Side Lane", listOf(junction, GeoPoint(53.003, -2.003)),
            mapOf("maxspeed" to "20 mph"))
        val right = Road("way/right", "East Road", listOf(junction, GeoPoint(53.003, -1.997)),
            mapOf("maxspeed" to "40 mph"))
        val match = RoadMatch(current, 0.0, 0.0, .9)
        val previews = TurnLimitDetector().detect(fix(53.0015), match, 30, listOf(current, left, right))
        assertEquals(listOf(TurnDirection.LEFT, TurnDirection.RIGHT), previews.map { it.direction })
        assertEquals(listOf(20, 40), previews.map { it.mph })
        assertTrue(previews.all { it.distanceM in 150.0..180.0 })
        assertTrue(TurnLimitDetector().detect(fix(53.001), match, 30,
            listOf(current, left, right)).isEmpty())
        assertEquals(30, SpeedLimits.mph(current.tags))
        assertTrue(TurnLimitDetector().detect(fix(53.0015).copy(accuracyM = 40.0), match, 30,
            listOf(current, left, right)).isEmpty())
        assertEquals(listOf(TurnDirection.LEFT), TurnLimitDetector().detect(fix(53.0015), match, 30,
            listOf(current, left, right.copy(tags = emptyMap()))).map { it.direction })
        assertTrue(TurnLimitDetector().detect(fix(53.0015), match, 30,
            listOf(current, left, left.copy(id = "way/ambiguous"), right)).none { it.direction == TurnDirection.LEFT })
    }
    @Test fun redLightAndDuplicateAlerts() {
        val camera = Camera("red", GeoPoint(53.005, -2.0), CameraType.RED_LIGHT, CameraSource.USER)
        val detector = CameraApproachDetector(); val match = RoadMatch(road, 0.0, 0.0, .9)
        assertEquals(CameraType.RED_LIGHT, detector.evaluate(fix(53.003), match, listOf(camera), 28.0).first?.camera?.type)
        assertEquals("Approach active", detector.evaluate(fix(53.0035), match, listOf(camera), 28.0).second.reason)
        detector.evaluate(fix(53.0051), match, listOf(camera), 28.0)
        assertEquals("Already passed", detector.evaluate(fix(53.0049), match, listOf(camera), 28.0).second.reason)
    }
    @Test fun overspeedGateRearms() {
        val gate = OverspeedGate()
        assertFalse(gate.update(30.0, 30, 2)); assertFalse(gate.update(33.0, null, 2))
        assertTrue(gate.update(33.0, 30, 2)); assertFalse(gate.update(34.0, 30, 2))
        assertFalse(gate.update(30.0, 30, 2)); assertTrue(gate.update(33.0, 30, 2))
    }
    @Test fun lowerLimitTriggersOneOverspeedTransition() {
        val gate = OverspeedGate()
        assertFalse(gate.update(28.0, 30, 2))
        assertTrue(gate.update(28.0, 20, 2))
        assertFalse(gate.update(28.0, 20, 2))
        assertFalse(gate.update(19.0, 20, 2))
        assertTrue(gate.update(26.0, 20, 2))
    }
    @Test fun geometryDistancesAndDirections() {
        assertEquals(500.0, Geo.distance(GeoPoint(53.0, -2.0), GeoPoint(53.0045, -2.0)), 5.0)
        assertEquals(0.0, Geo.bearing(GeoPoint(53.0, -2.0), GeoPoint(53.0045, -2.0)), .01)
        assertEquals(180.0, Geo.difference(0.0, 180.0), .01)
    }
    @Test fun coveragePrefetchesAheadBeforeLeavingKnownGeometry() {
        val center = GeoPoint(53.0, -2.0)
        val snapshot = OsmSnapshot(center, 1000, listOf(road), emptyList())
        val position = GeoPoint(53.003, -2.0)
        assertTrue(snapshot.usable(position, 2000))
        val target = OsmCoverage.refreshTarget(snapshot, fix(53.003).copy(bearing = 0.0), 2000, 0)
        assertNotNull(target)
        assertTrue(target!!.lat > position.lat)
        assertNull(OsmCoverage.refreshTarget(snapshot, fix(53.0002), 2000, 0))
        assertNull(OsmCoverage.refreshTarget(snapshot, fix(53.003), 2000, 1500))
        assertFalse(snapshot.usable(GeoPoint(53.012, -2.0), 2000))
    }
    @Test fun knownLimitSurvivesBriefMatchingJitterButNotRoadChange() {
        val stable = RoadLimitStabilizer()
        val start = fix(53.005)
        val match = RoadMatch(road, 2.0, 0.0, .9)
        assertEquals(30, stable.resolve(start, match, 30, 1000))
        assertEquals(30, stable.resolve(start.copy(elapsedMs = 2000), null, null, 2000))
        assertEquals(30, stable.resolve(start.copy(elapsedMs = 10_000), null, null, 10_000))
        assertNull(stable.resolve(start.copy(point = GeoPoint(53.005, -1.999)), null, null, 11_000))
        assertEquals(30, stable.resolve(start, match, 30, 12_000))
        assertNull(stable.resolve(start.copy(elapsedMs = 45_000), null, null, 45_000))
        assertEquals(30, stable.resolve(start, match, 30, 46_000))
        val sideRoad = road.copy(id = "way/2", tags = emptyMap())
        assertNull(stable.resolve(start, RoadMatch(sideRoad, 2.0, 0.0, .9), null, 47_000))
    }

    @Test fun connectedSameLimitRoadStaysKnownDuringAmbiguousMatch() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val next = current.copy(id = "way/next", points = listOf(current.points.last(), GeoPoint(53.006, -2.0)))
        val stable = RoadLimitStabilizer()
        assertEquals(30, stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 1000))
        assertEquals(30, stable.resolve(fix(53.0034), null, null, 2000, listOf(current, next)))
    }

    @Test fun differentKnownLimitBecomesCurrentOnlyAfterTheJunction() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val next = current.copy(id = "way/next", points = listOf(current.points.last(), GeoPoint(53.006, -2.0)),
            tags = mapOf("maxspeed" to "20 mph"))
        val stable = RoadLimitStabilizer()
        stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 1000)
        assertEquals(30, stable.resolve(fix(53.0029), null, null, 1500, listOf(current, next)))
        assertEquals(20, stable.resolve(fix(53.0034), null, null, 2000, listOf(current, next)))
    }

    @Test fun actualTurnUsesKnownSideRoadLimitWithoutChangingBeforeTurn() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val left = Road("way/left", "Side Lane", listOf(current.points.last(), GeoPoint(53.003, -2.003)),
            mapOf("maxspeed" to "20 mph"))
        val stable = RoadLimitStabilizer()
        assertEquals(30, stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 1000))
        assertEquals(30, stable.resolve(fix(53.0029), null, null, 1500, listOf(current, left)))
        assertEquals(20, stable.resolve(fix(53.003).copy(point = GeoPoint(53.003, -2.0005),
            bearing = 270.0), null, null, 2000, listOf(current, left)))
    }

    @Test fun untaggedOrUnconnectedRoadNeverInheritsAnOldLimit() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val unknown = Road("way/unknown", "Other Road", listOf(current.points.last(), GeoPoint(53.006, -2.0)), emptyMap())
        val remote = current.copy(id = "way/remote", points = listOf(GeoPoint(53.0032, -2.0), GeoPoint(53.006, -2.0)))
        val stable = RoadLimitStabilizer()
        stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 1000)
        assertNull(stable.resolve(fix(53.0034), null, null, 2000, listOf(current, unknown)))
        stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 3000)
        assertNull(stable.resolve(fix(53.0034), null, null, 4000, listOf(current, remote)))
    }

    @Test fun overlappingKnownAndUnknownContinuationsStayUnknown() {
        val current = road.copy(points = listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)))
        val next = current.copy(id = "way/known", points = listOf(current.points.last(), GeoPoint(53.006, -2.0)))
        val unknown = next.copy(id = "way/untagged", tags = emptyMap())
        val stable = RoadLimitStabilizer()
        stable.resolve(fix(53.0028), RoadMatch(current, 0.0, 0.0, .9), 30, 1000)
        assertNull(stable.resolve(fix(53.0034), null, null, 2000, listOf(current, next, unknown)))
    }
}
