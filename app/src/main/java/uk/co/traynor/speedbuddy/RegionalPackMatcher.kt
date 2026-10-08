package uk.co.traynor.speedbuddy

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max

/** Read-only matcher for verified schema-v1 packs. It deliberately returns a provider state, not a guessed limit. */
internal class RegionalPackMatcher(context: Context) {
    private val packs = RegionalPackStore(context)
    private val matcher = RoadMatcher()

    /** Context is bounded separately from point matching, so look-ahead never changes the current road. */
    data class Result(val state: RoadProviderState,val match: RoadMatch?,val generation: String?=null,
        val candidates: List<Road> = emptyList(),val contextComplete: Boolean=true,
        val packDetails: List<RegionalPackDescriptor> = emptyList(),val coverage: Boolean?=null,val error: String?=null)
    private var lastGeneration: String?=null
    private data class Batch(val roads: List<Road>,val complete: Boolean,val corrupt: Boolean)

    @Synchronized
    fun match(fix: Fix): Result=packs.reading { matchReading(fix) }
    private fun matchReading(fix: Fix): Result {
        val snapshot=packs.snapshot()
        val generation=snapshot.generation
        if(lastGeneration!=generation) { matcher.reset();lastGeneration=generation }
        val participating=mutableListOf<RegionalPackDescriptor>()
        var readFailed=snapshot.unavailable
        fun result(state: RoadProviderState,match: RoadMatch?,candidates: List<Road> = emptyList(),complete: Boolean=true,coverage: Boolean?=null)=
            Result(state,match,generation,candidates,complete,
                participating.ifEmpty { snapshot.installed.map { it.descriptor } },coverage,
                if(readFailed) "Some regional pack files are corrupt, missing or unreadable" else if(!complete) "Regional geometry incomplete or conflicting" else null)
        if(!validPoint(fix.point) || fix.accuracyM !in 1.0..25.0)
            return result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null)
        val near=mutableListOf<Road>();val context=mutableListOf<Road>()
        var covered=false;var unavailable=snapshot.unavailable;var uncertain=false;var contextComplete=true
        for(installed in snapshot.installed) {
            var pointCovered=false;var coverageRead=false
            runCatching {
                SQLiteDatabase.openDatabase(installed.database.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                    pointCovered=covers(db,fix.point);coverageRead=true
                    if(pointCovered) {
                        covered=true;participating+=installed.descriptor
                        val pointRoads=roadsNear(db,fix,minOf(60.0,maxOf(20.0,3.0*fix.accuracyM)))
                        uncertain=uncertain || !pointRoads.complete || pointRoads.corrupt
                        near+=pointRoads.roads.filter { Geo.projection(fix.point,it.points).first<=maxOf(20.0,fix.accuracyM*1.5) }
                    }
                    // Installed neighboring regions may supply forward geometry without
                    // being allowed to establish the current point's road authority.
                    val lookAhead=roadsNear(db,fix,350.0)
                    contextComplete=contextComplete && lookAhead.complete && !lookAhead.corrupt
                    context+=lookAhead.roads
                }
            }.onFailure {
                unavailable=true;readFailed=true
                if(coverageRead) contextComplete=false
                if(pointCovered) uncertain=true
            }
        }
        val currentGeneration=packs.snapshot().generation
        if(currentGeneration!=generation) return Result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,currentGeneration,contextComplete=false)
        if(!covered) return result(if(unavailable) RoadProviderState.SERVICE_UNAVAILABLE else RoadProviderState.COVERAGE_UNAVAILABLE,null,coverage=if(unavailable) null else false)
        // Conflicting overlapping regional facts must not be resolved by file enumeration order.
        fun conflicts(roads: List<Road>)=roads.groupBy { it.id }.values.any { group ->
            group.map { r -> r.tags.filterKeys { it.startsWith("maxspeed") || it.startsWith("source:maxspeed") || it in setOf("oneway","highway","junction") } }.distinct().size>1 }
        uncertain=uncertain || conflicts(near)
        contextComplete=contextComplete && !conflicts(context)
        val unique=context.distinctBy { it.id }
        if(uncertain) return result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,unique,false,true)
        val match=matcher.match(fix,near.distinctBy { it.id }) ?: return result(RoadProviderState.ROAD_MATCH_UNCERTAIN,null,unique,contextComplete,true)
        val limit=PackSpeedLimits.mph(match.road.tags,fix.bearing,match)
        return result(if(limit==null) RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN else RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,match,unique,contextComplete,true)
    }

    private fun roadsNear(db: SQLiteDatabase,fix: Fix,radius: Double): Batch {
        val latDelta=radius/111319.49079327358
        val lonDelta=radius/(111319.49079327358*cos(Math.toRadians(fix.point.lat)))
        return db.rawQuery("""SELECT r.osm_way_id,r.coordinates,r.tags FROM roads_rtree x
            JOIN roads r ON r.osm_way_id=x.osm_way_id
            WHERE x.max_lon>=? AND x.min_lon<=? AND x.max_lat>=? AND x.min_lat<=? ORDER BY r.osm_way_id LIMIT 513""",
            arrayOf("${fix.point.lon-lonDelta}","${fix.point.lon+lonDelta}","${fix.point.lat-latDelta}","${fix.point.lat+latDelta}")).use { cursor ->
            val roads=mutableListOf<Road>();var corrupt=false;var count=0
            while(cursor.moveToNext()) {
                count++
                if(count>512) break
                runCatching {
                    val id=cursor.getLong(0);require(id>0)
                    val json=JSONObject(cursor.getString(2))
                    val tags=json.keys().asSequence().associateWith { key -> require(json.get(key) is String);json.getString(key) }
                    val array=JSONArray(cursor.getString(1));require(array.length() in 2..20_000)
                    val points=List(array.length()) { i -> array.getJSONArray(i).let { require(it.length()==2);GeoPoint(it.getDouble(1),it.getDouble(0)) } }
                    require(points.all(::validPoint))
                    Road("way/$id",tags["name"],points,tags)
                }.onSuccess(roads::add).onFailure { corrupt=true }
            }
            Batch(roads,count<=512,corrupt)
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
    private fun unsupported(tags: Map<String,String>) = tags.keys.any {
        it.startsWith("maxspeed") && ("conditional" in it || "variable" in it || "lanes" in it)
    }
    fun undirected(tags: Map<String,String>): Int? {
        if(unsupported(tags) || "maxspeed:forward" in tags || "maxspeed:backward" in tags) return null
        return parse(tags["maxspeed"] ?: tags["maxspeed:type"] ?: tags["source:maxspeed"])
    }
    private fun value(tags: Map<String,String>,bearing: Double?,match: RoadMatch): String? {
        if(unsupported(tags)) return null
        val base=tags["maxspeed"] ?: tags["maxspeed:type"] ?: tags["source:maxspeed"]
        if("maxspeed:forward" !in tags && "maxspeed:backward" !in tags) return base
        val key=when(WayTravelDirection.from(match,bearing)) {
            WayTravelDirection.FORWARD -> "maxspeed:forward"
            WayTravelDirection.BACKWARD -> "maxspeed:backward"
            null -> return null
        }
        return tags[key] ?: base
    }
    fun mph(tags: Map<String,String>,bearing: Double?,match: RoadMatch)=parse(value(tags,bearing,match))
    fun national(tags: Map<String,String>,bearing: Double?,match: RoadMatch)=
        value(tags,bearing,match)?.trim()?.lowercase() in setOf("gb:nsl_single","gb:nsl_dual","gb:motorway")
    private fun parse(raw: String?): Int? {
        val value=raw?.trim()?.lowercase() ?: return null
        when(value) {
            "gb:nsl_single" -> return 60
            "gb:nsl_dual", "gb:motorway" -> return 70
            "gb:nsl_restricted" -> return 30
        }
        val m=Regex("^(\\d{1,3})(?:\\s*(mph|km/h|kmh|kph))?$").matchEntire(value) ?: return null
        val number=m.groupValues[1].toDouble()
        val mph=if(m.groupValues[2]=="mph") number else number/1.609344
        return mph.takeIf { it>=5 && it<=130 }?.let { kotlin.math.round(it).toInt() }
    }
}
