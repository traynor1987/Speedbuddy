package uk.co.traynor.speedbuddy

object CameraLimits {
    fun resolve(camera: Camera, road: RoadMatch?, roadLimit: Int?): Int? =
        camera.enforcedMph?.takeIf { it > 0 } ?: roadLimit?.takeIf {
            it > 0 && road != null && road.confidence >= .55 &&
                Geo.projection(camera.point, road.road.points).first <= 20
        }
}

/** A spoken limit is either tagged on the camera or confidently matched to the current road. */
object CameraAnnouncement {
    fun text(camera: Camera, matchedRoadLimitMph: Int?, speeding: Boolean = false): String {
        val cameraPhrase = when (camera.type) {
            CameraType.MOBILE -> "Mobile speed camera reported ahead."
            CameraType.SPEED -> "Speed camera ahead."
            CameraType.RED_LIGHT -> "Red light camera ahead."
            CameraType.COMBINED -> "Red light and speed camera ahead."
            CameraType.AVERAGE -> "Average speed camera ahead."
        }
        val prefix = camera.junction?.let { group ->
            val name = if (group.name.endsWith("junction", ignoreCase=true)) group.name else "${group.name} junction"
            cameraPhrase.removeSuffix(".") + " at $name."
        } ?: cameraPhrase
        val limit = camera.enforcedMph ?: matchedRoadLimitMph
        val message = if (limit != null && limit > 0) "$prefix Camera limit $limit miles per hour." else prefix
        return if (speeding && limit != null && limit > 0) "Warning, speeding. $message" else message
    }
}
