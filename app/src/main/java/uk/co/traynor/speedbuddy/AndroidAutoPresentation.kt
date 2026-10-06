package uk.co.traynor.speedbuddy

/**
 * Pure, host-independent projection of the authoritative phone driving state.
 * The car surface has no controls and does not start or stop DrivingService.
 */
data class AndroidAutoPresentation(
    val hero: String,
    val speed: String,
    val status: String?,
    val upcoming: String?,
    val camera: String?,
)

object AndroidAutoPresenter {
    fun present(state: DriveState): AndroidAutoPresentation {
        val limit = state.limitMph?.let { "$it mph" } ?: "Limit unavailable"
        val hero = if (state.limitDecision?.assumed == true && state.limitMph != null) "$limit ⚠" else limit
        val speed = state.speedMph?.let { "${it.toInt()} mph" } ?: "GPS speed unavailable"
        val status = when {
            !state.active -> "Open Speed Buddy on your phone and start driving"
            state.limitDecision?.assumed == true -> "Assumed — not confirmed"
            else -> null
        }
        val upcoming = state.upcoming?.let { next ->
            val distance = next.distanceM?.takeIf { it.isFinite() && it >= 0 }?.let(::yards)
            buildString { append("Upcoming ${next.mph} mph"); if (distance != null) append(" in $distance") }
        }
        val camera = state.alert?.let { alert ->
            val distance = alert.distanceM.takeIf { it.isFinite() && it >= 0 }?.let(::yards)
            buildString { append("CAMERA AHEAD"); if (alert.camera.enforcedMph != null) append(" — ${alert.camera.enforcedMph} mph"); if (distance != null) append(" — $distance") }
        }
        return AndroidAutoPresentation(hero, speed, status, upcoming, camera)
    }
    private fun yards(metres: Double): String = "${(metres * 1.09361).toInt()} yd"
}
