package uk.co.traynor.speedbuddy

import java.util.UUID

/** Local observations only. The direction is the reporting vehicle's travel bearing, not proof of enforcement. */
data class MobileReport(
    val id: String, val point: GeoPoint, val reportedAtMs: Long, val observedAtMs: Long,
    val expiresAtMs: Long, val lifetimeMinutes: Int, val direction: Double? = null,
    val roadId: String? = null, val roadName: String? = null,
    val source: String = "Speed Buddy · this phone",
) : java.io.Serializable {
    init {
        require(id.startsWith("mobile:") && id.length <= 100)
        require(point.lat.isFinite() && point.lat in -90.0..90.0 && point.lon.isFinite() && point.lon in -180.0..180.0)
        require(lifetimeMinutes in MobileReportCapture.lifetimes)
        require(reportedAtMs > 0 && observedAtMs >= reportedAtMs && expiresAtMs > observedAtMs &&
            expiresAtMs - observedAtMs == lifetimeMinutes * 60_000L)
        require(direction == null || direction.isFinite() && direction in 0.0..<360.0)
        require(roadId == null || roadId.startsWith("way/") && roadId.length <= 100)
        require((roadName?.length ?: 0) <= 200 && source.length <= 100)
    }
    fun activeAt(nowMs: Long) = nowMs in observedAtMs until expiresAtMs
    fun confirm(nowMs: Long): MobileReport? = if (activeAt(nowMs))
        copy(observedAtMs=nowMs,expiresAtMs=nowMs+lifetimeMinutes*60_000L) else null
    fun sameEncounter(new: MobileReport): Boolean = activeAt(new.reportedAtMs) &&
        new.reportedAtMs-observedAtMs in 0..120_000 && roadId == new.roadId &&
        Geo.distance(point,new.point)<=40 && when {
            direction == null || new.direction == null -> direction == new.direction
            else -> Geo.difference(direction,new.direction)<=35
        }
    fun asCamera() = Camera(id,point,CameraType.MOBILE,CameraSource.USER,direction,
        updatedAtMs=observedAtMs,mobileReport=this)
}

object MobileReportCapture {
    val lifetimes = listOf(30,60,120,240)
    fun create(fix: Fix?, road: RoadMatch?, elapsedNowMs: Long, wallNowMs: Long,
        lifetimeMinutes: Int): MobileReport? {
        if (fix == null || elapsedNowMs-fix.elapsedMs !in 0..5_000 ||
            !fix.accuracyM.isFinite() || fix.accuracyM !in 0.0..35.0 || wallNowMs<=0) return null
        val minutes=lifetimeMinutes.takeIf { it in lifetimes } ?: 120
        val direction=fix.bearing?.takeIf { it.isFinite() && it in 0.0..<360.0 &&
            fix.speedMps?.let { speed -> speed.isFinite() && speed>=2.3 }==true }
        val matched=road?.takeIf { it.confidence>=.55 && it.distanceM<=20 &&
            Geo.projection(fix.point,it.road.points).first<=20 }
        return MobileReport("mobile:${UUID.randomUUID()}",fix.point,wallNowMs,wallNowMs,
            wallNowMs+minutes*60_000L,minutes,direction,matched?.road?.id,matched?.road?.name?.take(200))
    }
}

object CameraAlertPolicy {
    fun enabled(type: CameraType, fixed: Boolean, mobile: Boolean, speed: Boolean, red: Boolean) = when(type) {
        CameraType.MOBILE -> mobile
        CameraType.SPEED,CameraType.AVERAGE -> fixed && speed
        CameraType.RED_LIGHT -> fixed && red
        CameraType.COMBINED -> fixed && (speed || red)
    }
}

/** Known OSM identity strengthens the existing geometry filter without rejecting a connected continuation. */
object MobileRoadRelevance {
    fun differentRoad(camera: Camera, current: RoadMatch?, roads: List<Road>): Boolean {
        val report=camera.mobileReport ?: return false
        val matched=current?.takeIf { it.confidence>=.55 } ?: return false
        if(report.roadId==null || report.roadId==matched.road.id) return false
        val reported=roads.firstOrNull { it.id==report.roadId } ?: return false
        val projection=Geo.projection(camera.point,matched.road.points)
        if(projection.first<=8 || projection.third !in .02.. .98 || Geo.projection(camera.point,reported.points).first>20) return false
        val connected=matched.road.points.any { p -> reported.points.any { Geo.distance(p,it)<=3 } }
        return !connected
    }
}

fun mobileAgeLabel(report: MobileReport, nowMs: Long): String {
    val minutes=((nowMs-report.observedAtMs).coerceAtLeast(0)/60_000).toInt()
    val age=if(minutes==0) "just now" else if(minutes==1) "1 min ago" else "$minutes min ago"
    return (if(report.observedAtMs>report.reportedAtMs) "Confirmed " else "Reported ")+age
}
