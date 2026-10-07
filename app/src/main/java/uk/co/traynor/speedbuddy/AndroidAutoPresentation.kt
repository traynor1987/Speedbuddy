package uk.co.traynor.speedbuddy

/** Pure projection of the authoritative phone driving state for the car host. */
enum class AndroidAutoLimitSign { UNKNOWN, MPH_20, MPH_30, MPH_40, MPH_50, MPH_60, MPH_70 }

data class AndroidAutoPresentation(
    val limitSign: AndroidAutoLimitSign,
    val limitLabel: String,
    val speed: String,
    val confidence: String?,
    val upcoming: String?,
    val camera: String?,
    val status: String?,
)

object AndroidAutoPresenter {
    fun present(state: DriveState): AndroidAutoPresentation {
        val limit = state.limitMph
        return AndroidAutoPresentation(
            limitSign = signFor(limit),
            limitLabel = limit?.let { "$it mph" } ?: "—",
            speed = state.speedMph?.let { "${it.toInt()} MPH" } ?: "— MPH",
            confidence = if (state.limitDecision?.assumed == true && limit != null) "⚠ Assumed — not confirmed" else null,
            upcoming = state.upcoming?.takeIf { it.isCredibleUpcoming(limit) }?.let { "NEXT ${it.mph} mph · ${yards(it.distanceM)}" },
            camera = state.alert?.let(::cameraText),
            status = if (!state.active) "Open Speed Buddy on your phone and start driving" else null,
        )
    }

    fun signFor(limit: Int?): AndroidAutoLimitSign = when (limit) {
        20 -> AndroidAutoLimitSign.MPH_20
        30 -> AndroidAutoLimitSign.MPH_30
        40 -> AndroidAutoLimitSign.MPH_40
        50 -> AndroidAutoLimitSign.MPH_50
        60 -> AndroidAutoLimitSign.MPH_60
        70 -> AndroidAutoLimitSign.MPH_70
        else -> AndroidAutoLimitSign.UNKNOWN
    }

    private fun cameraText(alert: Alert): String = buildString {
        append("⚠ ").append(cameraType(alert.camera.type))
        alert.camera.enforcedMph?.let { append(" · $it mph") }
        alert.distanceM.takeIf { it.isFinite() && it >= 0.0 }?.let { append(" · ${yards(it)}") }
    }

    private fun cameraType(type: CameraType): String = when (type) {
        CameraType.SPEED -> "SPEED CAMERA"
        CameraType.RED_LIGHT -> "RED-LIGHT CAMERA"
        CameraType.COMBINED -> "SPEED + RED-LIGHT CAMERA"
        CameraType.AVERAGE -> "AVERAGE-SPEED CAMERA"
        CameraType.MOBILE -> "MOBILE CAMERA"
    }

    private fun yards(metres: Double): String = "${(metres * 1.09361).toInt()} yd"
}

/** A preview is useful only while it is a different limit genuinely ahead. */
internal fun UpcomingLimit.isCredibleUpcoming(currentLimit: Int?): Boolean =
    distanceM.isFinite() && distanceM > 5.0 && (currentLimit == null || mph != currentLimit)
