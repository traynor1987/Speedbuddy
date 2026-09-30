package uk.co.traynor.speedbuddy

/** Precomputed geometry bounds avoid projecting against every road on every GPS fix. */
class RoadSpatialIndex(roads: List<Road>) {
    private val regions = roads.filter { it.points.size > 1 }.map { road ->
        Entry(road,road.points.minOf { it.lat },road.points.maxOf { it.lat },
            road.points.minOf { it.lon },road.points.maxOf { it.lon })
    }
    private data class Entry(val road: Road,val south: Double,val north: Double,val west: Double,val east: Double)
    fun nearby(point: GeoPoint): List<Road> = regions.filter {
        point.lat+.003>=it.south && point.lat-.003<=it.north && point.lon+.005>=it.west && point.lon-.005<=it.east
    }.map { it.road }
}

/** Unknown gaps don't announce the same known limit a second time. */
class LimitChangeGate {
    private var previous: Int? = null
    fun update(limit: Int?): Int? {
        if(limit==null) return null
        val changed=previous!=null && previous!=limit
        previous=limit
        return limit.takeIf { changed }
    }
}

class DeferredLimitVoice {
    private val changes = LimitChangeGate()
    private var pending: Int? = null
    fun update(limit: Int?, busy: Boolean, enabled: Boolean): Int? {
        val changed = changes.update(limit)
        if (!enabled || limit == null) { pending = null; return null }
        if (changed != null) pending = changed
        if (pending != limit) pending = null
        if (busy) return null
        return pending.also { pending = null }
    }
}
