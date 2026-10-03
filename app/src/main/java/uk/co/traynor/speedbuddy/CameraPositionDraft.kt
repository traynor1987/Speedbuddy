package uk.co.traynor.speedbuddy

/** A proposed position belongs to the selected camera, including after state restoration. */
data class CameraPositionDraft(val cameraId: String, val point: GeoPoint): java.io.Serializable {
    fun effectiveFor(camera: Camera): GeoPoint = if(camera.id==cameraId) point else camera.point
}
