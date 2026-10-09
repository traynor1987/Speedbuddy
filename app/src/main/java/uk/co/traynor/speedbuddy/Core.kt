package uk.co.traynor.speedbuddy

import kotlin.math.*

internal const val MPS_TO_MPH = 2.2369362921

data class GeoPoint(val lat: Double, val lon: Double) : java.io.Serializable
data class Fix(
    val point: GeoPoint, val accuracyM: Double, val speedMps: Double?,
    val speedAccuracyMps: Double?, val bearing: Double?, val elapsedMs: Long,
)
data class Road(val id: String, val name: String?, val points: List<GeoPoint>, val tags: Map<String, String>) : java.io.Serializable
enum class CameraType { SPEED, RED_LIGHT, COMBINED, AVERAGE, MOBILE }
enum class CameraSource { OSM, USER, LUFOP }
data class Camera(
    val id: String, val point: GeoPoint, val type: CameraType, val source: CameraSource,
    val direction: Double? = null, val enforcedMph: Int? = null, val note: String? = null,
    val updatedAtMs: Long = 0L, val bidirectional: Boolean = false,
    val aliasIds: Set<String> = emptySet(),
    val locallyCorrected: Boolean = false,
    val mobileReport: MobileReport? = null,
    val junction: CameraJunction? = null,
) : java.io.Serializable
data class RoadMatch(val road: Road, val distanceM: Double, val headingDifference: Double?, val confidence: Double, val wayHeading: Double? = null)
data class CameraDecision(val camera: Camera?, val distanceM: Double?, val accepted: Boolean, val reason: String, val bearingDifference: Double? = null)
data class CameraWarning(val announceApproach: Boolean, val closeReminder: Boolean,
    val speeding: Boolean, val limitMph: Int?) {
    val doubleBeep: Boolean get() = closeReminder || speeding
}
data class Alert(val camera: Camera, val distanceM: Double, val warning: CameraWarning? = null)

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
    fun mph(tags: Map<String,String>): Int? = PackSpeedLimits.undirected(tags)
}

/** Bridge brief GPS ambiguity using observed road geometry or a connected, tagged road. */
class RoadLimitStabilizer {
    private var lastMatch: RoadMatch? = null
    private var lastLimit: Int? = null
    private var lastSeenMs: Long = 0
    private var candidateId: String? = null
    private var candidateSince = 0L
    private var candidateCount = 0
    private var candidateFixElapsedMs: Long? = null
    fun resolve(fix: Fix, match: RoadMatch?, limit: Int?, nowMs: Long, roads: List<Road> = emptyList()): Int? {
        if (match != null) {
            if (limit != null) {
                val prior = lastMatch
                if(prior==null && (match.confidence<.7 || fix.accuracyM>20 ||
                    (fix.bearing != null && (match.headingDifference ?: 90.0)>30) ||
                    (fix.bearing == null && (fix.accuracyM>8 || match.distanceM>fix.accuracyM || match.confidence<.85)))) return null
                if (prior != null && lastLimit != limit) {
                    val candidate = "${match.road.id}|$limit"
                    if (candidateId != candidate) { candidateId = candidate; candidateSince = nowMs; candidateCount = 0; candidateFixElapsedMs = null }
                    // Weak/side-road matches never become authoritative just because time passed.
                    // Strong, aligned fixes may confirm a real short zone in a few seconds.
                    if (fix.accuracyM <= 20 && match.confidence >= .8 &&
                        (match.headingDifference ?: 90.0) <= 25 && fix.bearing != null) {
                        if(candidateFixElapsedMs!=fix.elapsedMs) { candidateCount++;candidateFixElapsedMs=fix.elapsedMs }
                    } else {
                        candidateCount = 0; candidateSince = nowMs
                    }
                    val junction = listOf(prior.road.points.first(),prior.road.points.last()).flatMap { a -> listOf(match.road.points.first(),match.road.points.last()).map { b -> a to b } }
                        .filter { Geo.distance(it.first,it.second) < 15 }
                        .minByOrNull { Geo.distance(fix.point,it.first) }?.first
                    val crossed = junction == null || fix.bearing?.let { passedBoundary(fix,junction,it) } == true
                    if (candidateCount < 3 || nowMs-candidateSince < 2000 || !crossed) {
                        val distance = Geo.projection(fix.point,prior.road.points).first
                        return lastLimit.takeIf { distance <= max(30.0,fix.accuracyM*2) || junction != null && distance < 150 }
                    }
                }
                candidateId = null; candidateCount = 0
                lastMatch = match; lastLimit = limit; lastSeenMs = nowMs
                return limit
            }
            // Weak matching to a tagged road is brief ambiguity, not a confirmed unknown road.
            if(match.confidence>=.35 || SpeedLimits.mph(match.road.tags)==null) { reset();return null }
        }
        val previous = lastMatch ?: return null
        if (nowMs - lastSeenMs !in 0..30_000 || fix.accuracyM > 25) { reset(); return null }
        val (distance, heading, _) = Geo.projection(fix.point, previous.road.points)
        val direction = fix.bearing?.let { b -> heading?.let { h ->
            min(Geo.difference(b, h), Geo.difference(b, (h + 180) % 360))
        } }
        if (distance <= max(16.0, fix.accuracyM * 1.5) && (direction == null || direction <= 45)) {
            return lastLimit
        }
        val bearing = fix.bearing ?: return resetAndUnknown()
        val nearbyJunctions = previous.road.points.filter { Geo.distance(it, fix.point) <= 100.0 }
        val candidates = roads.asSequence().filter { road ->
            road.id != previous.road.id && road.points.size > 1 &&
                nearbyJunctions.any { junction ->
                    Geo.distance(junction, road.points.first()) <= 12.0 ||
                        Geo.distance(junction, road.points.last()) <= 12.0
                }
        }.mapNotNull { road ->
            val (roadDistance, roadHeading, _) = Geo.projection(fix.point, road.points)
            val headingDifference = roadHeading?.let { heading ->
                if (road.tags["oneway"] == "yes") Geo.difference(bearing, heading)
                else min(Geo.difference(bearing, heading), Geo.difference(bearing, (heading + 180) % 360))
            } ?: return@mapNotNull null
            if (roadDistance <= max(12.0, fix.accuracyM * 1.2) && headingDifference <= 40)
                road to SpeedLimits.mph(road.tags) else null
        }.toList()
        if (candidates.any { it.second == null }) return resetAndUnknown()
        val knownLimits = candidates.map { it.second }.distinct()
        if (knownLimits.size != 1) return resetAndUnknown()
        if (candidates.size == 1) {
            lastMatch = RoadMatch(candidates.single().first, 0.0, null, .35)
            lastLimit = knownLimits.single()
            lastSeenMs = nowMs
        }
        return knownLimits.single()
    }
    private fun resetAndUnknown(): Int? { reset(); return null }
    fun reset() { lastMatch = null; lastLimit = null; lastSeenMs = 0; candidateId = null; candidateCount = 0 }
}

data class UpcomingLimit(val mph: Int, val distanceM: Double, val national: Boolean, val uncertain: Boolean = false,val roadId: String? = null)
enum class TurnDirection { LEFT, RIGHT }
data class TurnLimit(val mph: Int, val distanceM: Double, val direction: TurnDirection,
    val national: Boolean)

/** Shows conditional side-road limits, never an assumed route or a new current limit. */
class TurnLimitDetector {
    fun detect(fix: Fix, current: RoadMatch?, currentMph: Int?, roads: List<Road>): List<TurnLimit> {
        val road = current?.road ?: return emptyList()
        val heading = fix.bearing ?: return emptyList()
        if (currentMph == null || fix.accuracyM > 25 || current.confidence < .7) return emptyList()
        val junctions = road.points.filter { point ->
            val distance = Geo.distance(fix.point, point)
            distance in 25.0..LIMIT_PREVIEW_METERS &&
                Geo.difference(heading, Geo.bearing(fix.point, point)) <= 35.0
        }
        val candidates = junctions.flatMap { junction ->
            val distance = Geo.distance(fix.point, junction)
            roads.asSequence().filter { it.id != road.id && it.points.size > 1 }
                .mapNotNull { next ->
                    val outgoing=RoadLookAhead.outgoing(next,junction) ?: return@mapNotNull null
                    val turn = (outgoing - heading + 540.0) % 360.0 - 180.0
                    val direction = when {
                        turn in -140.0..-40.0 -> TurnDirection.LEFT
                        turn in 40.0..140.0 -> TurnDirection.RIGHT
                        else -> return@mapNotNull null
                    }
                    val nextMatch=RoadMatch(next,0.0,0.0,1.0,Geo.projection(junction,next.points).second)
                    val mph=PackSpeedLimits.mph(next.tags,outgoing,nextMatch) ?: return@mapNotNull null
                    if (mph == currentMph) return@mapNotNull null
                    TurnLimit(mph, distance, direction,
                        PackSpeedLimits.national(next.tags,outgoing,nextMatch))
                }.toList()
        }
        return listOf(TurnDirection.LEFT, TurnDirection.RIGHT).mapNotNull { direction ->
            candidates.filter { it.direction == direction }.singleOrNull()
        }
    }
}

/** 300 imperial yards for camera alert onset. */
const val CAMERA_ALERT_METERS = 274.32
const val CAMERA_CLOSE_METERS = 91.44
/** 200 imperial yards for conditional and straight-ahead speed-limit previews. */
const val LIMIT_PREVIEW_METERS = 182.88

/** Legal outgoing geometry, shared by turns and straight-ahead regional previews. */
internal object RoadLookAhead {
    fun outgoing(road: Road,junction: GeoPoint): Double? {
        val oneWay=road.tags["oneway"]?.lowercase()
        val forward=oneWay!="-1"
        val backward=oneWay !in setOf("yes","1","true") && road.tags["junction"] !in setOf("roundabout","circular")
        return when {
            forward && Geo.distance(road.points.first(),junction)<12 -> Geo.bearing(road.points[0],road.points[1])
            backward && Geo.distance(road.points.last(),junction)<12 -> Geo.bearing(road.points.last(),road.points[road.points.lastIndex-1])
            else -> null
        }
    }
}

/** Preview only connected, unambiguous continuations; nearby side roads never establish identity. */
class UpcomingLimitDetector {
    fun detect(fix: Fix,current: RoadMatch?,currentMph: Int?,roads: List<Road>): UpcomingLimit? {
        val road=current?.road ?: return null
        val heading=fix.bearing ?: return null
        if(currentMph==null || fix.accuracyM>25 || road.points.size<2 || current.confidence<.7) return null
        val direction=WayTravelDirection.from(current.copy(wayHeading=Geo.projection(fix.point,road.points).second),heading) ?: return null
        val oneWay=road.tags["oneway"]?.lowercase()
        if(direction==WayTravelDirection.FORWARD && oneWay=="-1" ||
            direction==WayTravelDirection.BACKWARD && (oneWay in setOf("yes","1","true") || road.tags["junction"] in setOf("roundabout","circular"))) return null
        val junction=if(direction==WayTravelDirection.FORWARD) road.points.last() else road.points.first()
        val distance=Geo.distance(fix.point,junction)
        if(distance !in 0.0..LIMIT_PREVIEW_METERS || Geo.difference(heading,Geo.bearing(fix.point,junction))>35) return null
        val identity=road.tags["ref"] ?: road.name ?: return null
        fun follow(at: GeoPoint,atHeading: Double,travelled: Double,seen: Set<String>): UpcomingLimit? {
            if(seen.size>16 || travelled>LIMIT_PREVIEW_METERS) return null
            val next=roads.filter { it.id !in seen && it.points.size>1 && (it.tags["ref"] ?: it.name)==identity }
                .mapNotNull { r -> RoadLookAhead.outgoing(r,at)?.takeIf { Geo.difference(atHeading,it)<=35 }?.let { r to it } }
                .singleOrNull() ?: return null
            val (r,outgoing)=next
            val match=RoadMatch(r,0.0,0.0,1.0,Geo.projection(at,r.points).second)
            val mph=PackSpeedLimits.mph(r.tags,outgoing,match) ?: return null
            if(mph!=currentMph) return if(travelled>=25.0) UpcomingLimit(mph,travelled,PackSpeedLimits.national(r.tags,outgoing,match),roadId=r.id) else null
            val forward=Geo.distance(at,r.points.first())<12
            val end=if(forward) r.points.last() else r.points.first()
            val endHeading=if(forward) Geo.bearing(r.points[r.points.lastIndex-1],end) else Geo.bearing(r.points[1],end)
            val length=r.points.zipWithNext().sumOf { Geo.distance(it.first,it.second) }
            return follow(end,endHeading,travelled+length,seen+r.id)
        }
        return follow(junction,heading,distance,setOf(road.id))
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
                    if (road.tags["oneway"] == "-1") Geo.difference(b, (heading + 180) % 360)
                    else if (road.tags["oneway"] in listOf("yes", "1", "true")) Geo.difference(b, heading)
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
            (1.0 - first.second / 40.0 - (first.third.first ?: 0.0) / 120.0).coerceIn(0.0, 1.0),
            Geo.projection(fix.point, first.first.points).second)
    }
    fun reset() { previous = null }
}

class CameraApproachDetector {
    private val notified = mutableSetOf<String>()
    private val closeNotified = mutableSetOf<String>()
    private val speedingNotified = mutableSetOf<String>()
    private val passed = mutableSetOf<String>()
    private val approached = mutableSetOf<String>()
    private val previousDistance = mutableMapOf<String, Double>()
    private var activeCameraId: String? = null
    private val lastSeen = mutableMapOf<String, Long>()
    private val encounterPoints = mutableMapOf<String, GeoPoint>()
    private val encounterMembers = mutableMapOf<String, MutableSet<String>>()
    fun evaluate(fix: Fix, road: RoadMatch?, cameras: List<Camera>, speedMph: Double?, roads: List<Road> = emptyList(), nowMs: Long = System.currentTimeMillis(), matchedRoadLimitMph: Int? = null, toleranceMph: Int = 2): Pair<Alert?, CameraDecision> {
        val activeCameras = cameras.filter { it.type != CameraType.MOBILE || it.mobileReport?.activeAt(nowMs) == true }
        // Leaving the encounter area rearms a camera. Merely stopping or GPS jitter cannot rearm it.
        encounterPoints.filter { Geo.distance(fix.point,it.value)>850 && fix.accuracyM<=35 }.keys.toList().forEach {
            clearEncounter(it)
        }
        activeCameras.forEach { camera ->
            val key = CameraEncounters.key(camera)
            lastSeen[key] = fix.elapsedMs
            encounterMembers.getOrPut(key) { mutableSetOf() }.add(camera.id)
        }
        lastSeen.filterValues { fix.elapsedMs - it > 600_000 }.keys.toList().forEach {
            clearEncounter(it)
        }
        if (fix.accuracyM > 35) {
            val active = activeCameras.firstOrNull { it.id == activeCameraId && it.id !in passed }
            return active?.let { Alert(it, previousDistance[it.id] ?: Geo.distance(fix.point, it.point)) } to
                CameraDecision(active, null, active != null, "Last known approach · GPS weak")
        }
        if (speedMph == null || speedMph < 5 || fix.bearing == null) {
            val active = activeCameras.firstOrNull { it.id == activeCameraId && it.id !in passed }
            val distance = active?.let { Geo.distance(fix.point, it.point) }
            if (active != null && distance != null && distance <= CAMERA_ALERT_METERS + 35) {
                return Alert(active, distance) to CameraDecision(active, distance, true, "Approach active")
            }
            activeCameraId = null
            return null to CameraDecision(null, null, false, "GPS, heading or movement insufficient")
        }
        val candidates = activeCameras.map { it to Geo.distance(fix.point, it.point) }.filter { it.second < 900 }.sortedBy { it.second }
        var diagnostic = CameraDecision(null, null, false, "No nearby camera")
        for ((camera, distance) in candidates) {
            val encounter = CameraEncounters.key(camera)
            val bearingDiff = Geo.difference(fix.bearing, Geo.bearing(fix.point, camera.point))
            val (roadDistance, _, roadFraction) = road?.let { Geo.projection(camera.point, it.road.points) } ?: Triple(0.0, null, 0.0)
            val otherCarriageway=road?.takeIf { it.confidence>=.55 && roadDistance>12 && roadFraction in .02.. .98 }?.let { current ->
                val heading=Geo.projection(camera.point,current.road.points).second
                roads.any { other->
                    if(other.id==current.road.id) false else {
                        val alternative=Geo.projection(camera.point,other.points)
                        val parallel=heading!=null && alternative.second!=null && minOf(Geo.difference(heading,alternative.second!!),Geo.difference(heading,(alternative.second!!+180)%360))<20
                        alternative.first<=6 && alternative.first+8<roadDistance && alternative.third in .02.. .98 && parallel
                    }
                }
            }==true
            val reason = when {
                camera.id in passed -> "Already passed"
                MobileRoadRelevance.differentRoad(camera,road,roads) -> "Different reported road"
                otherCarriageway -> "Different carriageway"
                bearingDiff > 65 -> "Camera behind or off heading"
                !CameraDirections.applies(camera.direction, fix.bearing, camera.bidirectional) -> "Opposite enforced direction"
                // A camera beyond the mapped way's endpoint can be on the next segment of this road.
                road != null && roadDistance > 30 && roadFraction in 0.02..0.98 -> "Different road"
                previousDistance[camera.id]?.let { distance > it + 25 } == true -> "Travelling away"
                else -> "Approaching"
            }
            previousDistance[camera.id] = distance
            if (reason == "Camera behind or off heading" && distance < 120 && camera.id in approached) passed += camera.id
            if (reason != "Approaching") { diagnostic = CameraDecision(camera, distance, false, reason, bearingDiff); continue }
            if (encounter in notified) {
                approached += camera.id
                activeCameraId = camera.id
                return Alert(camera, distance, warning(camera, distance, false, speedMph, road, matchedRoadLimitMph, toleranceMph)) to
                    CameraDecision(camera, distance, true, "Approach active", bearingDiff)
            }
            if (distance <= CAMERA_ALERT_METERS) {
                approached += camera.id
                notified += encounter; activeCameraId = camera.id;encounterPoints[encounter]=camera.junction?.point ?: camera.point
                return Alert(camera, distance, warning(camera, distance, true, speedMph, road, matchedRoadLimitMph, toleranceMph)) to
                    CameraDecision(camera, distance, true, "New approach", bearingDiff)
            }
            diagnostic = CameraDecision(camera, distance, false, "Beyond alert range", bearingDiff)
        }
        activeCameraId = null
        return null to diagnostic
    }
    // Only called for a moving, accurate, relevant approach. Weak GPS and stops retain the card
    // without consuming a cue. Each stage shares the encounter's existing rearm boundary.
    private fun warning(camera: Camera, distance: Double, newApproach: Boolean, speed: Double,
        road: RoadMatch?, roadLimit: Int?, tolerance: Int): CameraWarning? {
        if (distance > CAMERA_ALERT_METERS) return null
        val limit = CameraLimits.resolve(camera, road, roadLimit)
        val key = CameraEncounters.key(camera)
        val close = distance <= CAMERA_CLOSE_METERS && closeNotified.add(key)
        val speeding = speed.isFinite() && limit != null && speed > limit + tolerance.coerceAtLeast(0) &&
            speedingNotified.add(key)
        return if (newApproach || close || speeding) CameraWarning(newApproach, close, speeding, limit) else null
    }
    private fun clearEncounter(key: String) {
        notified.remove(key); closeNotified.remove(key); speedingNotified.remove(key)
        lastSeen.remove(key); encounterPoints.remove(key)
        val members = encounterMembers.remove(key).orEmpty()
        members.forEach { passed.remove(it); approached.remove(it); previousDistance.remove(it) }
        if (activeCameraId in members) activeCameraId = null
    }
    fun reset() {
        notified.clear(); closeNotified.clear(); speedingNotified.clear(); passed.clear(); approached.clear()
        previousDistance.clear(); activeCameraId = null; lastSeen.clear(); encounterPoints.clear(); encounterMembers.clear()
    }
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
