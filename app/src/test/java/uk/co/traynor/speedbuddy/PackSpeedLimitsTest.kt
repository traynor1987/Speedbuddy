package uk.co.traynor.speedbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackSpeedLimitsTest {
    private val match = RoadMatch(Road("osm:1", null, listOf(GeoPoint(53.0,-2.0), GeoPoint(53.001,-2.0)), emptyMap()), 1.0, 0.0, .95)
    @Test fun `uses explicit supported mph`() = assertEquals(30, PackSpeedLimits.mph(mapOf("maxspeed" to "30 mph"), 0.0, match))
    @Test fun `bare values are kilometres per hour`() = assertEquals(31, PackSpeedLimits.mph(mapOf("maxspeed" to "50"), 0.0, match))
    @Test fun `conditional and lane values remain Unknown`() {
        assertNull(PackSpeedLimits.mph(mapOf("maxspeed" to "30 mph", "maxspeed:conditional" to "20 @ (Mo-Fr)"), 0.0, match))
        assertNull(PackSpeedLimits.mph(mapOf("maxspeed:lanes" to "20|30"), 0.0, match))
    }
}
