package uk.co.traynor.speedbuddy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max

/** Read-only matcher for verified schema-v1 packs. It deliberately returns a provider state, not a guessed limit. */
internal class RegionalPackMatcher(context: Context) {
    private val packs = RegionalPackStore(context)
    private val matcher = RoadMatcher()

    data class Result(val state: RoadProviderState, val match: RoadMatch?, val generation: String? = null)

    fun match(fix: Fix): Result {
        if (fix.accuracyM !in 1.0..25.0 || fix.speedMps?.let { it < 2.0 } == true || fix.bearing !in 0.0..<360.0)
            return Result(RoadProviderState.ROAD_MATCH_UNCERTAIN, null)
        val databases = packs.activeDatabases()
        if (databases.isEmpty()) return Result(RoadProviderState.COVERAGE_UNAVAILABLE, null)
        val candidates = mutableListOf<Road>()
        var covered = false
        for ((region, database) in databases) {
            SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                if (!covers(db, fix.point)) return@use
                covered = true
                candidates += roadsNear(db, fix)
            }
        }
        if (!covered) return Result(RoadProviderState.COVERAGE_UNAVAILABLE, null)
        if (candidates.size > 64) return Result(RoadProviderState.ROAD_MATCH_UNCERTAIN, null)
        val match = matcher.match(fix, candidates.distinctBy { it.id })
            ?: return Result(RoadProviderState.ROAD_MATCH_UNCERTAIN, null)
        val limit = PackSpeedLimits.mph(match.road.tags, fix.bearing!!, match)
        return Result(if (limit == null) RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN else RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,
            match, region)
    }

    private fun roadsNear(db: SQLiteDatabase, fix: Fix): List<Road> {
        val radius = minOf(60.0, maxOf(20.0, 3.0 * fix.accuracyM))
        val latDelta = radius / 111319.49079327358
        val lonDelta = radius / (111319.49079327358 * cos(Math.toRadians(fix.point.lat)))
        return db.rawQuery("""SELECT r.osm_way_id,r.coordinates,r.tags FROM roads_rtree x
            JOIN roads r ON r.osm_way_id=x.osm_way_id
            WHERE x.max_lon>=? AND x.min_lon<=? AND x.max_lat>=? AND x.min_lat<=? LIMIT 65""",
            arrayOf("${fix.point.lon-lonDelta}", "${fix.point.lon+lonDelta}", "${fix.point.lat-latDelta}", "${fix.point.lat+latDelta}")).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val tags = JSONObject(cursor.getString(2)).let { json -> json.keys().asSequence().associateWith { json.optString(it) } }
                val points = JSONArray(cursor.getString(1)).let { array -> List(array.length()) { i -> array.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } } }
                if (points.size >= 2) add(Road("osm:${cursor.getLong(0)}", tags["name"], points, tags))
            } }
        }
    }

    private fun covers(db: SQLiteDatabase, point: GeoPoint): Boolean = db.rawQuery("SELECT value FROM metadata WHERE key='coverage'", null).use { c ->
        c.moveToFirst() && JSONArray(c.getString(0)).let { polygons -> (0 until polygons.length()).any { p ->
            val rings = polygons.getJSONArray(p)
            pointIn(rings.getJSONArray(0), point) && (1 until rings.length()).none { pointIn(rings.getJSONArray(it), point) }
        } }
    }
    private fun pointIn(ring: JSONArray, point: GeoPoint): Boolean {
        var inside = false; var j = ring.length()-1
        for (i in 0 until ring.length()) { val a=ring.getJSONArray(i); val b=ring.getJSONArray(j)
            val ay=a.getDouble(1); val by=b.getDouble(1)
            if ((ay>point.lat)!=(by>point.lat) && point.lon < (b.getDouble(0)-a.getDouble(0))*(point.lat-ay)/(by-ay)+a.getDouble(0)) inside=!inside
            j=i
        }; return inside
    }
}

internal object PackSpeedLimits {
    fun mph(tags: Map<String,String>, bearing: Double, match: RoadMatch): Int? {
        if (listOf("maxspeed:conditional","maxspeed:variable","maxspeed:lanes").any(tags::containsKey)) return null
        val forward = match.headingDifference?.let { it <= 45.0 } == true
        val key = if (forward) "maxspeed:forward" else "maxspeed:backward"
        return parse(tags[key] ?: tags["maxspeed"])
    }
    private fun parse(raw: String?): Int? {
        val m = Regex("^(\\d{1,3})(?:\\s*(mph|km/h|kmh|kph))?$").matchEntire(raw?.trim()?.lowercase() ?: return null) ?: return null
        val value=m.groupValues[1].toDouble(); val mph=if(m.groupValues[2]=="mph") value else value/1.609344
        return mph.takeIf { it>0 && it<=130 }?.let { kotlin.math.round(it).toInt() }
    }
}
