package uk.co.traynor.speedbuddy

/** A spoken limit is either tagged on the camera or confidently matched to the current road. */
object CameraAnnouncement {
    fun text(camera: Camera, matchedRoadLimitMph: Int?): String {
        val prefix = when (camera.type) {
            CameraType.MOBILE -> "Mobile speed camera reported ahead."
            CameraType.SPEED -> "Speed camera ahead."
            CameraType.RED_LIGHT -> "Red light camera ahead."
            CameraType.COMBINED -> "Red light and speed camera ahead."
            CameraType.AVERAGE -> "Average speed camera ahead."
        }
        val limit = camera.enforcedMph ?: matchedRoadLimitMph
        return if (limit != null && limit > 0) "$prefix Speed limit $limit miles per hour." else prefix
    }
}
