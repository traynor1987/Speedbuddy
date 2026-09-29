package uk.co.traynor.speedbuddy

import kotlin.math.*

internal const val MPS_TO_MPH = 2.2369362921

data class GeoPoint(val lat: Double, val lon: Double)
data class Fix(
    val point: GeoPoint, val accuracyM: Double, val speedMps: Double?,
    val speedAccuracyMps: Double?, val bearing: Double?, val elapsedMs: Long,
)
data class Road(val id: String, val name: String?, val points: List<GeoPoint>, val tags: Map<String, String>)
enum class CameraType { SPEED, RED_LIGHT, COMBINED, AVERAGE }
enum class CameraSource { OSM, USER, LUFOP }
data class Camera(
    val id: String, val point: GeoPoint, val type: CameraType, val source: CameraSource,
    val direction: Double? = null, val enforcedMph: Int? = null, val note: String? = null,
    val updatedAtMs: Long = 0L, val bidirectional: Boolean = false,
)
data class RoadMatch(val road: Road, val distanceM: Double, val headingDifference: Double?, val confidence: Double)
data class CameraDecision(val camera: Camera?, val distanceM: Double?, val accepted: Boolean, val reason: String, val bearingDifference: Double? = null)
data class Alert(val camera: Camera, val distanceM: Double)

object Geo {
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p = Math.toRadians(a.lat); val q = Math.toRadians(b.lat)
        val dLat = q - p; val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(p) * cos(q) * sin(dLon / 2).pow(2)
        return 12742000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p = Math.toRadians(a.lat); val q = Math.toRadians(b.lat)
        val d = Math.toRadians(b.lon - a.lon)
        return (Math.toDegrees(atan2(sin(d) * cos(q), cos(p) * sin(q) - sin(p) * cos(q) * cos(d))) + 360) % 360
    }
    fun difference(a: Double, b: Double): Double = abs((a - b + 540) % 360 - 180)
    fun ahead(point: GeoPoint, bearing: Double, meters: Double): GeoPoint {
        val radians = Math.toRadians(bearing)
        return GeoPoint(point.lat + meters * cos(radians) / 111195.0,
            point.lon + meters * sin(radians) / (111320.0 * cos(Math.toRadians(point.lat))))
    }
    // Local tangent plane, sufficient for the small (<= 2 km) fetched region.
    fun projection(p: GeoPoint, points: List<GeoPoint>): Triple<Double, Double?, Double> {
        if (points.size < 2) return Triple(Double.POSITIVE_INFINITY, null, 0.0)
        val scaleX = 111320.0 * cos(Math.toRadians(p.lat)); val scaleY = 111195.0
        var best = Double.POSITIVE_INFINITY; var heading: Double? = null; var fraction = 0.0
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lon - p.lon) * scaleX; val ay = (a.lat - p.lat) * scaleY
            val bx = (b.lon - p.lon) * scaleX; val by = (b.lat - p.lat) * scaleY
            val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
            if (len < 1.0) continue
            val t = (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
            val dist = hypot(ax + t * dx, ay + t * dy)
            if (dist < best) { best = dist; heading = bearing(a, b); fraction = (i + t) / points.lastIndex }
        }
        return Triple(best, heading, fraction)
    }
}

class SpeedFilter {
    private var prior: Double? = null
    private var at: Long = 0
    fun update(fix: Fix, nowMs: Long): Double? {
        val raw = fix.speedMps ?: return invalidate()
        if (nowMs - fix.elapsedMs !in 0..5000 || fix.accuracyM > 50 || raw !in 0.0..75.0 ||
            (fix.speedAccuracyMps != null && fix.speedAccuracyMps > 4.5)) return invalidate()
        val mph = raw * MPS_TO_MPH
        if (prior != null && at > 0 && fix.elapsedMs > at &&
            abs(mph - prior!!) > (18.0 * (fix.elapsedMs - at) / 1000.0 + 12.0)) return prior
        val smooth = if (mph < 2.0) 0.0 else prior?.let { it * .35 + mph * .65 } ?: mph
        prior = smooth; at = fix.elapsedMs
        return smooth
    }
    fun current(nowMs: Long): Double? = if (at > 0 && nowMs - at in 0..5000) prior else invalidate()
    private fun invalidate(): Double? { prior = null; at = 0; return null }
}

object SpeedLimits {
    fun mph(tags: Map<String, String>): Int? {
        if (tags["maxspeed:conditional"] != null || tags["maxspeed:variable"] != null ||
            tags["maxspeed:lanes"] != null || tags["maxspeed:forward"] != null || tags["maxspeed:backward"] != null) return null
        val raw = (tags["maxspeed"] ?: tags["maxspeed:type"] ?: tags["source:maxspeed"])
            ?.trim()?.lowercase() ?: return null
        if (raw == "gb:nsl_single") return 60
        if (raw == "gb:nsl_dual" || raw == "gb:motorway") return 70
        if (raw == "gb:nsl_restricted") return 30
        val match = Regex("^(\\d{1,3})(?:\\s*(mph|km/h|kmh|kph))?$").matchEntire(raw) ?: return null
        val value = match.groupValues[1].toInt(); if (value !in 5..130) return null
        return if (match.groupValues[2] == "mph") value else (value * .621371).roundToInt()
    }
}

/** Hold only a recently observed limit on the same geometry through a short ambiguous GPS fix. */
class RoadLimitStabilizer {
    private var lastMatch: RoadMatch? = null
    private var lastLimit: Int? = null
    private var lastSeenMs: Long = 0
    fun resolve(fix: Fix, match: RoadMatch?, limit: Int?, nowMs: Long): Int? {
        if (match != null) {
            if (limit != null) { lastMatch = match; lastLimit = limit; lastSeenMs = nowMs }
            else reset()
            return limit
        }
        val previous = lastMatch ?: return null
        if (nowMs - lastSeenMs !in 0..30_000 || fix.accuracyM > 25) { reset(); return null }
        val (distance, heading, _) = Geo.projection(fix.point, previous.road.points)
        val direction = fix.bearing?.let { b -> heading?.let { h ->
            min(Geo.difference(b, h), Geo.difference(b, (h + 180) % 360))
        } }
        if (distance > max(16.0, fix.accuracyM * 1.5) || direction != null && direction > 45) {
            reset(); return null
        }
        return lastLimit
    }
    fun reset() { lastMatch = null; lastLimit = null; lastSeenMs = 0 }
}

data class UpcomingLimit(val mph: Int, val distanceM: Double, val national: Boolean)
enum class TurnDirection { LEFT, RIGHT }
data class TurnLimit(val mph: Int, val distanceM: Double, val direction: TurnDirection,
    val national: Boolean)

/** Shows conditional side-road limits, never an assumed route or a new current limit. */
class TurnLimitDetector {
    fun detect(fix: Fix, current: RoadMatch?, currentMph: Int?, roads: List<Road>): List<TurnLimit> {
        val road = current?.road ?: return emptyList()
        val heading = fix.bearing ?: return emptyList()
        if (currentMph == null || fix.accuracyM > 25 || current.confidence < .35) return emptyList()
        val junctions = road.points.filter { point ->
            val distance = Geo.distance(fix.point, point)
            distance in 25.0..CAMERA_ALERT_METERS &&
                Geo.difference(heading, Geo.bearing(fix.point, point)) <= 35.0
        }
        val candidates = junctions.flatMap { junction ->
            val distance = Geo.distance(fix.point, junction)
            roads.asSequence().filter { it.id != road.id && it.points.size > 1 }
                .mapNotNull { next ->
                    val outgoing = when {
                        Geo.distance(next.points.first(), junction) < 12.0 ->
                            Geo.bearing(next.points[0], next.points[1])
                        next.tags["oneway"] != "yes" && Geo.distance(next.points.last(), junction) < 12.0 ->
                            Geo.bearing(next.points.last(), next.points[next.points.lastIndex - 1])
                        else -> return@mapNotNull null
                    }
                    val turn = (outgoing - heading + 540.0) % 360.0 - 180.0
                    val direction = when {
                        turn in -140.0..-40.0 -> TurnDirection.LEFT
                        turn in 40.0..140.0 -> TurnDirection.RIGHT
                        else -> return@mapNotNull null
                    }
                    val mph = SpeedLimits.mph(next.tags) ?: return@mapNotNull null
                    if (mph == currentMph) return@mapNotNull null
                    TurnLimit(mph, distance, direction,
                        next.tags["maxspeed"]?.startsWith("GB:nsl") == true ||
                            next.tags["maxspeed:type"]?.startsWith("GB:nsl") == true)
                }.toList()
        }
        return listOf(TurnDirection.LEFT, TurnDirection.RIGHT).mapNotNull { direction ->
            candidates.filter { it.direction == direction }.singleOrNull()
        }
    }
}

/** 300 imperial yards, shared by alert onset and the junction preview window. */
const val CAMERA_ALERT_METERS = 274.32

/** Preview only a connected continuation of the current named road, never a nearby side road. */
class UpcomingLimitDetector {
    fun detect(fix: Fix, current: RoadMatch?, currentMph: Int?, roads: List<Road>): UpcomingLimit? {
        val road = current?.road ?: return null
        val heading = fix.bearing ?: return null
        if (currentMph == null || fix.accuracyM > 25 || road.points.size < 2) return null
        val end = road.points.last()
        val start = road.points.first()
        val forward = Geo.difference(heading, Geo.bearing(road.points[road.points.lastIndex - 1], end)) < 40
        val backward = Geo.difference(heading, Geo.bearing(road.points[1], start)) < 40
        val junction = when {
            forward -> end
            backward && road.tags["oneway"] != "yes" -> start
            else -> return null
        }
        val distance = Geo.distance(fix.point, junction)
        if (distance !in 25.0..450.0 || Geo.difference(heading, Geo.bearing(fix.point, junction)) > 35) return null
        val identity = road.tags["ref"] ?: road.name ?: return null
        val candidates = roads.asSequence().filter { it.id != road.id && it.points.size > 1 &&
            (it.tags["ref"] ?: it.name) == identity }
            .mapNotNull { next ->
                val nextHeading = when {
                    Geo.distance(next.points.first(), junction) < 12.0 -> Geo.bearing(next.points[0], next.points[1])
                    next.tags["oneway"] != "yes" && Geo.distance(next.points.last(), junction) < 12.0 ->
                        Geo.bearing(next.points.last(), next.points[next.points.lastIndex - 1])
                    else -> return@mapNotNull null
                }
                val mph = SpeedLimits.mph(next.tags)
                if (mph == null || mph == currentMph || Geo.difference(heading, nextHeading) > 35) null
                else UpcomingLimit(mph, distance, next.tags["maxspeed:type"]?.startsWith("GB:nsl") == true)
            }.toList()
        return candidates.singleOrNull()
    }
}

class RoadMatcher {
    private var previous: String? = null
    fun match(fix: Fix, roads: List<Road>): RoadMatch? {
        if (fix.accuracyM > 35) return null
        val candidates = roads.mapNotNull { road ->
            val (distance, heading, _) = Geo.projection(fix.point, road.points)
            if (distance > max(20.0, fix.accuracyM * 1.5) || heading == null) null else {
                val direction = fix.bearing?.let { b ->
                    if (road.tags["oneway"] == "yes") Geo.difference(b, heading)
                    else min(Geo.difference(b, heading), Geo.difference(b, (heading + 180) % 360))
                }
                if (direction != null && direction > 55) null else {
                    val score = distance + (direction ?: 0.0) * .25 - if (road.id == previous) 12.0 else 0.0
                    Triple(road, distance, Pair(direction, score))
                }
            }
        }.sortedBy { it.third.second }
        val first = candidates.firstOrNull() ?: return null
        // A close second road (parallel carriageway/service road) is ambiguous unless history resolves it.
        if (candidates.size > 1 && candidates[1].third.second - first.third.second < 8.0 && first.first.id != previous) return null
        previous = first.first.id
        return RoadMatch(first.first, first.second, first.third.first,
            (1.0 - first.second / 40.0 - (first.third.first ?: 0.0) / 120.0).coerceIn(0.0, 1.0))
    }
    fun reset() { previous = null }
}

class CameraApproachDetector {
    private val notified = mutableSetOf<String>()
    private val passed = mutableSetOf<String>()
    private val previousDistance = mutableMapOf<String, Double>()
    fun evaluate(fix: Fix, road: RoadMatch?, cameras: List<Camera>, speedMph: Double?): Pair<Alert?, CameraDecision> {
        if (fix.accuracyM > 35 || speedMph == null || speedMph < 5 || fix.bearing == null)
            return null to CameraDecision(null, null, false, "GPS, heading or movement insufficient")
        val candidates = cameras.map { it to Geo.distance(fix.point, it.point) }.filter { it.second < 900 }.sortedBy { it.second }
        var diagnostic = CameraDecision(null, null, false, "No nearby camera")
        for ((camera, distance) in candidates) {
            val bearingDiff = Geo.difference(fix.bearing, Geo.bearing(fix.point, camera.point))
            val (roadDistance, _, roadFraction) = road?.let { Geo.projection(camera.point, it.road.points) } ?: Triple(0.0, null, 0.0)
            val reason = when {
                camera.id in passed -> "Already passed"
                bearingDiff > 65 -> "Camera behind or off heading"
                !CameraDirections.applies(camera.direction, fix.bearing, camera.bidirectional) -> "Opposite enforced direction"
                // A camera beyond the mapped way's endpoint can be on the next segment of this road.
                road != null && roadDistance > 30 && roadFraction in 0.02..0.98 -> "Different road"
                previousDistance[camera.id]?.let { distance > it + 25 } == true -> "Travelling away"
                else -> "Approaching"
            }
            previousDistance[camera.id] = distance
            if (reason == "Camera behind or off heading" && distance < 120 && camera.id in notified) passed += camera.id
            if (reason != "Approaching") { diagnostic = CameraDecision(camera, distance, false, reason, bearingDiff); continue }
            if (camera.id in notified) return Alert(camera, distance) to CameraDecision(camera, distance, true, "Approach active", bearingDiff)
            if (distance <= CAMERA_ALERT_METERS) { notified += camera.id; return Alert(camera, distance) to CameraDecision(camera, distance, true, "New approach", bearingDiff) }
            diagnostic = CameraDecision(camera, distance, false, "Beyond alert range", bearingDiff)
        }
        return null to diagnostic
    }
    fun reset() { notified.clear(); passed.clear(); previousDistance.clear() }
}

class OverspeedGate {
    private var armed = true
    fun update(speed: Double?, limit: Int?, tolerance: Int): Boolean {
        if (speed == null || limit == null) { armed = true; return false }
        if (speed <= limit + tolerance - 2) armed = true
        if (armed && speed > limit + tolerance) { armed = false; return true }
        return false
    }
    fun isOver(speed: Double?, limit: Int?, tolerance: Int) = speed != null && limit != null && speed > limit + tolerance
}
