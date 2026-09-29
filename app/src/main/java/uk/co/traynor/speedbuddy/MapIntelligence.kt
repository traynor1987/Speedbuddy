package uk.co.traynor.speedbuddy

import kotlin.math.floor
import kotlin.math.pow

/** Road edits are bound to an OSM way ID, never to a free-floating coordinate. */
object RoadSelection {
    fun select(point: GeoPoint, roads: List<Road>): Road? {
        val nearest = roads.asSequence().map { it to Geo.projection(point, it.points).first }
            .filter { it.second.isFinite() && it.second <= 18.0 }
            .sortedBy { it.second }.take(2).toList()
        val first = nearest.firstOrNull() ?: return null
        if (nearest.size > 1 && nearest[1].second - first.second < 8.0) return null
        return first.first
    }
}

/** A bearing denotes the vehicle's enforced travel direction, clockwise from north. */
object CameraDirections {
    fun applies(enforcedTravelBearing: Double?, vehicleBearing: Double, bidirectional: Boolean = false): Boolean =
        enforcedTravelBearing == null || Geo.difference(enforcedTravelBearing, vehicleBearing) <= 50.0 ||
            bidirectional && Geo.difference((enforcedTravelBearing + 180.0) % 360.0, vehicleBearing) <= 50.0
}

object CameraCategories {
    fun fromOsm(enforcement: String, highway: String): CameraType? {
        val parts = enforcement.lowercase().split(';', ',').map(String::trim)
        val speed = "maxspeed" in parts || highway == "speed_camera"
        val red = "traffic_signals" in parts || "red_light_camera" in parts
        return when {
            "average_speed" in parts -> CameraType.AVERAGE
            speed && red -> CameraType.COMBINED
            red -> CameraType.RED_LIGHT
            speed -> CameraType.SPEED
            else -> null
        }
    }
}

data class CameraMapGroup(val point: GeoPoint, val count: Int, val camera: Camera?)

/** Bounded viewport aggregation; grouping occurs off the UI thread. */
object CameraClustering {
    fun group(cameras: List<Camera>, zoom: Double): List<CameraMapGroup> {
        if (zoom >= 15.0) return cameras.map { CameraMapGroup(it.point, 1, it) }
        val cell = .06 / 2.0.pow((zoom - 10.0).coerceIn(0.0, 5.0))
        return cameras.groupBy { floor(it.point.lat / cell).toLong() to floor(it.point.lon / cell).toLong() }
            .values.map { group -> CameraMapGroup(
                GeoPoint(group.sumOf { it.point.lat } / group.size, group.sumOf { it.point.lon } / group.size),
                group.size, group.singleOrNull()) }
    }
}
