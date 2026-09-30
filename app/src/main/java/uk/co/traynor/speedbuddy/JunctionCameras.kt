package uk.co.traynor.speedbuddy

data class CameraJunction(val id: String, val name: String, val point: GeoPoint, val ways: Int) : java.io.Serializable

object JunctionRules {
    fun validate(junction: CameraJunction) {
        require(junction.id.startsWith("junction:") && junction.id.length in 10..100 &&
            junction.name.isNotBlank() && junction.name.length <= 60 && junction.ways in 4..5 &&
            junction.point.lat.isFinite() && junction.point.lat in -90.0..90.0 &&
            junction.point.lon.isFinite() && junction.point.lon in -180.0..180.0) { "Invalid junction" }
    }
    fun validateMember(camera: Camera, junction: CameraJunction) {
        validate(junction)
        require(camera.source == CameraSource.USER && camera.type in listOf(CameraType.SPEED, CameraType.RED_LIGHT, CameraType.COMBINED) &&
            camera.mobileReport == null && camera.direction != null && camera.direction.isFinite() && camera.direction in 0.0..<360.0 &&
            Geo.distance(camera.point, junction.point).isFinite() && Geo.distance(camera.point, junction.point) <= 300) {
            "Junction cameras need a known travel direction and a position within 300m of the junction"
        }
    }
}

object CameraEncounters {
    fun key(camera: Camera): String = camera.junction?.id ?: camera.id
}
