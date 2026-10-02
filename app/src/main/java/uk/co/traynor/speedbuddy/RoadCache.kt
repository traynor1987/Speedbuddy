package uk.co.traynor.speedbuddy

import kotlin.math.*

const val ROAD_FRESH_MS = 7 * 86_400_000L
const val ROAD_CHECK_MS = 15 * 60_000L
const val ROAD_RADIUS_M = 32_186.88

data class RoadTile(val y: Int, val x: Int) {
    val id get() = "$y:$x"
    val south get() = y * .08
    val north get() = (y + 1) * .08
    val west get() = x * .12
    val east get() = (x + 1) * .12
    val center get() = GeoPoint((south+north)/2, (west+east)/2)
    companion object {
        fun at(p: GeoPoint) = RoadTile(floor(p.lat/.08).toInt(),floor(p.lon/.12).toInt())
        fun parse(id: String): RoadTile = id.split(':').let { RoadTile(it[0].toInt(),it[1].toInt()) }
    }
}
object RoadTiles {
    fun covering(center: GeoPoint): Set<RoadTile> {
        // Latitude-dependent longitude width; target is a radius, not a square.
        val dy = ROAD_RADIUS_M/111195.0
        val dx = ROAD_RADIUS_M/(111320.0*cos(Math.toRadians(center.lat)).coerceAtLeast(.1))
        val low = RoadTile.at(GeoPoint(center.lat-dy,center.lon-dx))
        val high = RoadTile.at(GeoPoint(center.lat+dy,center.lon+dx))
        return buildSet {
            for (y in low.y..high.y) for (x in low.x..high.x) {
                val t = RoadTile(y,x)
                val closest = GeoPoint(center.lat.coerceIn(t.south,t.north),center.lon.coerceIn(t.west,t.east))
                if (Geo.distance(center,closest) <= ROAD_RADIUS_M) add(t)
            }
        }
    }
}
data class RoadTileData(val tile: RoadTile,val fetchedAt: Long,val roads: List<Road>,val cameras: List<Camera>,val complete: Boolean = true)
data class SavedRoad(val road: Road,val fetchedAt: Long)
data class LocalRoads(val roads: List<SavedRoad>,val cameras: List<Camera>)

/** Scheduling only. Cache age determines refresh, never whether a saved row may be matched. */
class RoadRefreshPlanner {
    var lastCheckMs = 0L; private set
    private var lastAttemptMs = Long.MIN_VALUE
    private var retryAt = 0L
    private var failures = 0
    private var desired: Set<RoadTile> = emptySet()
    private var plannedAt: GeoPoint? = null
    private var retentionAt: GeoPoint? = null
    fun retentionLimited(point: GeoPoint) { retentionAt=point }
    fun next(fix: Fix, coverage: Map<RoadTile,Long>, now: Long): RoadTile? {
        if (now < retryAt || lastAttemptMs != Long.MIN_VALUE && now-lastAttemptMs < 30_000) return null
        val local = RoadTile.at(fix.point)
        val ahead = fix.bearing?.takeIf { (fix.speedMps ?: 0.0) > 2.0 }?.let { RoadTile.at(Geo.ahead(fix.point,it,1800.0)) }
        if (plannedAt == null || now-lastCheckMs >= ROAD_CHECK_MS ||
            Geo.distance(plannedAt!!,fix.point) > 1500) {
            desired = RoadTiles.covering(fix.point); plannedAt = fix.point; lastCheckMs = now
        }
        fun needed(tile: RoadTile) = coverage[tile]?.let { now-it !in 0..ROAD_FRESH_MS } ?: true
        if (needed(local)) return local
        if (ahead != null && needed(ahead)) return ahead
        if(retentionAt?.let { Geo.distance(it,fix.point)<5000 } == true)
            return desired.filter { it in coverage && needed(it) }.minByOrNull { Geo.distance(fix.point,it.center) }
        retentionAt=null
        return desired.filter(::needed).minByOrNull { Geo.distance(fix.point,it.center) }
    }
    fun attempted(now: Long) { lastAttemptMs = now }
    fun succeeded() { failures = 0; retryAt = 0 }
    fun failed(now: Long) { failures = (failures+1).coerceAtMost(5); retryAt = now+(30_000L shl failures).coerceAtMost(ROAD_CHECK_MS) }
}

fun validPoint(p: GeoPoint) = p.lat.isFinite() && p.lon.isFinite() && p.lat in -85.0..85.0 && p.lon in -180.0..180.0
fun passedBoundary(fix: Fix, point: GeoPoint, bearing: Double): Boolean {
    val heading = fix.bearing ?: return false
    if (fix.accuracyM > 25 || Geo.difference(heading,bearing) > 40) return false
    val distance = Geo.distance(point,fix.point)
    val forward = distance*cos(Math.toRadians(Geo.difference(bearing,Geo.bearing(point,fix.point))))
    val lateral = abs(distance*sin(Math.toRadians(Geo.difference(bearing,Geo.bearing(point,fix.point)))))
    return forward >= max(5.0,fix.accuracyM*.6) && lateral <= max(35.0,fix.accuracyM*2)
}

/** Portable spatial buckets; Android framework SQLite does not guarantee the RTree extension. */
object RoadCells {
    fun at(p: GeoPoint)=floor(p.lat/.005).toInt() to floor(p.lon/.0075).toInt()
    fun forRoad(points: List<GeoPoint>,tile: RoadTile): Set<Pair<Int,Int>> {
        val dy=1500.0/111195.0;val dx=1500.0/(111320.0*cos(Math.toRadians(tile.center.lat)).coerceAtLeast(.1))
        return buildSet {
            points.zipWithNext().forEach { (a,b) ->
                val south=max(min(a.lat,b.lat),tile.south-dy);val north=min(max(a.lat,b.lat),tile.north+dy)
                val west=max(min(a.lon,b.lon),tile.west-dx);val east=min(max(a.lon,b.lon),tile.east+dx)
                if(south>north || west>east) return@forEach
                val low=at(GeoPoint(south,west));val high=at(GeoPoint(north,east))
                for(y in low.first..high.first) for(x in low.second..high.second) add(y to x)
            }
        }
    }
}
