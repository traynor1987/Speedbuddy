package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class TransitionRegressionTest {
    @Test fun oneBadMatchMustNotPromoteUpcomingTwentyToCurrent() {
        val road = Road("way/1", "Test road", listOf(GeoPoint(53.0, -2.0), GeoPoint(53.003, -2.0)), mapOf("maxspeed" to "30 mph"))
        val next = road.copy(id = "way/2", points = listOf(GeoPoint(53.003, -2.0), GeoPoint(53.008, -2.0)), tags = mapOf("maxspeed" to "20 mph"))
        val fix = Fix(GeoPoint(53.0028, -2.0), 8.0, 10.0, 1.0, 0.0, 1000)
        val stable = RoadLimitStabilizer()
        assertEquals(30, stable.resolve(fix, RoadMatch(road, 0.0, 0.0, .9), 30, 1000))
        assertEquals("A single next-segment match before the boundary must remain a preview", 30,
            stable.resolve(fix.copy(elapsedMs = 2000), RoadMatch(next, 22.0, 0.0, .45), 20, 2000))
    }
}
