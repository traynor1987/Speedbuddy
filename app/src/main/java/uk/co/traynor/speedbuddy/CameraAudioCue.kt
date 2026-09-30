package uk.co.traynor.speedbuddy

/** One audio sequence even when approach, proximity and speeding occur on the same fix. */
data class CameraAudioCue(val speech: String?, val doubleBeep: Boolean) {
    companion object {
        fun from(camera: Camera, warning: CameraWarning, voiceEnabled: Boolean): CameraAudioCue =
            CameraAudioCue(
                if (voiceEnabled && (warning.announceApproach || warning.speeding))
                    CameraAnnouncement.text(camera, warning.limitMph, warning.speeding) else null,
                warning.doubleBeep)
    }
}

internal data class QueuedCameraSpeech(val text: String, val queuedAtMs: Long,
    val alreadyBeeped: Boolean, val relevant: () -> Boolean, val voiceAllowed: () -> Boolean) {
    fun playableAt(nowMs: Long): Boolean = nowMs - queuedAtMs in 0..10_000 && relevant() && voiceAllowed()
}

object CameraCueValidity {
    fun relevant(alert: Alert?, speed: Double?, fresh: Boolean, enabled: Boolean,
        id: String, speeding: Boolean, limit: Int?, tolerance: Int, nowMs: Long,
        currentLimit: Int? = limit): Boolean =
        fresh && enabled && alert != null && alert.camera.id == id &&
            alert.distanceM <= CAMERA_ALERT_METERS + 35 &&
            (alert.camera.type != CameraType.MOBILE || alert.camera.mobileReport?.activeAt(nowMs) == true) &&
            (!speeding || (limit != null && currentLimit == limit && speed != null && speed.isFinite() && speed > limit + tolerance.coerceAtLeast(0)))
}
