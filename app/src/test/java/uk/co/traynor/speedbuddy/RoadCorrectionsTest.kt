package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RoadCorrectionsTest {
    private val road = Road("way/7", "Main Road", listOf(GeoPoint(53.0, -2.0),
        GeoPoint(53.003, -2.0)), mapOf("maxspeed" to "30 mph"))

    @Test fun unknownAndNationalCorrectionsKeepTheirMeaning() {
        val unknown = RoadLimitCorrection(road.id, RoadLimitKind.UNKNOWN, null, "30 mph", 1000)
        assertNull(SpeedLimits.mph(unknown.apply(road).tags))
        val national = RoadLimitCorrection(road.id, RoadLimitKind.NATIONAL_SINGLE, 60, "30 mph", 1000)
        assertEquals(60, SpeedLimits.mph(national.apply(road).tags))
        assertEquals("GB:nsl_single", national.apply(road).tags["maxspeed"])
    }

    @Test fun correctedNextRoadPreviewsOnlyAfterTheCurrentLimit() {
        val next = road.copy(id = "way/8", points = listOf(road.points.last(), GeoPoint(53.006, -2.0)))
        val correction = RoadLimitCorrection(next.id, RoadLimitKind.NUMERIC, 20, "30 mph", 1000)
        val fix = Fix(GeoPoint(53.001, -2.0), 5.0, 12.5, 1.0, 0.0, 1000)
        val current = RoadMatch(road, 0.0, 0.0, .9)
        assertEquals(30, SpeedLimits.mph(current.road.tags))
        assertEquals(20, UpcomingLimitDetector().detect(fix, current, 30,
            listOf(road, correction.apply(next)))?.mph)
        assertNull(UpcomingLimitDetector().detect(fix, current, 30,
            listOf(road, road.copy(id = next.id, points = next.points))) )
    }
}
