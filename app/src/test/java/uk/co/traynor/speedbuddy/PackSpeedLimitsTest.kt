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
    @Test fun `actual matcher preserves forward and reverse geometry rather than folded alignment`() {
        val road=match.road.copy(tags=mapOf("maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph"))
        fun limit(bearing: Double, road: Road): Int? {
            val fix=Fix(GeoPoint(53.0005,-2.0),5.0,10.0,1.0,bearing,1000)
            val actual=RoadMatcher().match(fix,listOf(road))!!
            return PackSpeedLimits.mph(road.tags,bearing,actual)
        }
        assertEquals(30,limit(0.0,road))
        assertEquals(50,limit(180.0,road))
        assertEquals(50,limit(0.0,road.copy(points=road.points.reversed())))
        assertEquals(30,limit(180.0,road.copy(points=road.points.reversed())))
    }
    @Test fun `directional limits require actual geometry and heading and respect reverse one way`() {
        val tags=mapOf("maxspeed:forward" to "30 mph","maxspeed:backward" to "50 mph")
        assertNull(PackSpeedLimits.mph(tags,0.0,match))
        val road=match.road.copy(tags=tags + ("oneway" to "-1"))
        val fix=Fix(GeoPoint(53.0005,-2.0),5.0,10.0,1.0,180.0,1000)
        val reverse=RoadMatcher().match(fix,listOf(road))!!
        assertEquals(50,PackSpeedLimits.mph(tags,fix.bearing,reverse))
        assertNull(RoadMatcher().match(fix.copy(bearing=0.0),listOf(road)))
        assertNull(PackSpeedLimits.mph(tags,null,reverse))
        assertNull(PackSpeedLimits.mph(tags,90.0,reverse))
    }
}
