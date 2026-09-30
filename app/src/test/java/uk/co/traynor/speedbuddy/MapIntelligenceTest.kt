package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class MapIntelligenceTest {
    private val centre = GeoPoint(53.55, -2.80)

    @Test fun selectsOnlyTheUnambiguousRoadSegment() {
        val road = Road("way/10", "High Street", listOf(
            GeoPoint(53.55, -2.801), GeoPoint(53.55, -2.799)), mapOf("maxspeed" to "30 mph"))
        val distant = road.copy(id = "way/11", points = listOf(
            GeoPoint(53.551, -2.801), GeoPoint(53.551, -2.799)))
        assertEquals("way/10", RoadSelection.select(centre, listOf(road, distant))?.id)
        assertNull(RoadSelection.select(centre, listOf(road, road.copy(id = "way/12"))))
        assertNull(RoadSelection.select(GeoPoint(53.6, -2.8), listOf(road)))
    }

    @Test fun cameraDirectionUsesTravelBearingAndNeverGuessesUnknown() {
        assertTrue(CameraDirections.applies(90.0, 92.0))
        assertFalse(CameraDirections.applies(270.0, 92.0))
        assertTrue(CameraDirections.applies(null, 270.0))
        assertTrue(CameraDirections.applies(359.0, 3.0))
        assertTrue(CameraDirections.applies(90.0, 270.0, bidirectional = true))
        assertFalse(CameraDirections.applies(90.0, 180.0, bidirectional = true))
    }

    @Test fun wideZoomClusteringRetainsCountsAndIndividualCameraAtDrivingZoom() {
        val cameras = (0 until 5_000).map { i -> Camera("camera-$i",
            GeoPoint(53.55 + i % 100 * .00001, -2.8 + i / 100 * .00001),
            CameraType.SPEED, CameraSource.LUFOP) }
        val clusters = CameraClustering.group(cameras, 10.0)
        assertTrue(clusters.size < 100)
        assertEquals(5_000, clusters.sumOf { it.count })
        val driving=CameraClustering.group(cameras,16.0)
        assertTrue(driving.size<=300)
        assertEquals(5_000,driving.sumOf { it.count })
        val sparse=CameraClustering.group(cameras.take(50),16.0)
        assertEquals(50,sparse.size)
        assertTrue(sparse.all { it.camera!=null && it.count==1 })
    }
    @Test fun osmEnforcementCategoriesDoNotCollapseCombinedOrAverageDevices() {
        assertEquals(CameraType.COMBINED,
            CameraCategories.fromOsm("maxspeed;traffic_signals", ""))
        assertEquals(CameraType.AVERAGE,
            CameraCategories.fromOsm("average_speed", ""))
        assertEquals(CameraType.SPEED,
            CameraCategories.fromOsm("", "speed_camera"))
        assertNull(CameraCategories.fromOsm("unknown", ""))
    }
}
