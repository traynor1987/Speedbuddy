package uk.co.traynor.speedbuddy

import org.json.JSONObject

/** A section is accepted only when explicit from/to nodes and connected section ways agree. */
data class AverageSpeedSection(val id: String,val points: List<GeoPoint>,val wayIds: Set<String>,val mph: Int?)
data class ActiveAverageSection(val section: AverageSpeedSection,val remainingM: Double)
object AverageSpeedSections {
    fun projection(point: GeoPoint,points: List<GeoPoint>): Triple<Double,Double?,Double> {
        val segments=points.zipWithNext();val lengths=segments.map { Geo.distance(it.first,it.second) }
        val total=lengths.sum();if(total<=0) return Triple(Double.POSITIVE_INFINITY,null,0.0)
        var best=Double.POSITIVE_INFINITY;var heading: Double?=null;var progress=0.0;var traversed=0.0
        segments.forEachIndexed { index,pair->
            val projected=Geo.projection(point,listOf(pair.first,pair.second))
            if(projected.first<best) { best=projected.first;heading=projected.second;progress=(traversed+lengths[index]*projected.third)/total }
            traversed+=lengths[index]
        }
        return Triple(best,heading,progress)
    }
    fun parse(relation: JSONObject): AverageSpeedSection? = runCatching {
        val tags=relation.getJSONObject("tags")
        if(tags.optString("enforcement")!="average_speed") return null
        val members=relation.getJSONArray("members")
        fun endpoint(role: String): GeoPoint? {
            val nodes=(0 until members.length()).map(members::getJSONObject)
                .filter { it.optString("role")==role && it.optString("type")=="node" && it.has("lat") && it.has("lon") }
            return nodes.singleOrNull()?.let { GeoPoint(it.getDouble("lat"),it.getDouble("lon")) }
        }
        val from=endpoint("from") ?: return null;val to=endpoint("to") ?: return null
        val ways=(0 until members.length()).map(members::getJSONObject)
            .filter { it.optString("role")=="section" && it.optString("type")=="way" }
        if(ways.isEmpty() || ways.size>100) return null
        val geometries=ways.map { member->
            val array=member.getJSONArray("geometry")
            require(array.length() in 2..5000)
            (0 until array.length()).map { i->val p=array.getJSONObject(i);GeoPoint(p.getDouble("lat"),p.getDouble("lon")) }
                .also { points->require(points.all { it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0 }) }
        }.toMutableList()
        val result=mutableListOf<GeoPoint>();var endpoint=from
        while(geometries.isNotEmpty()) {
            val possible=geometries.mapIndexedNotNull { index,points->
                when { Geo.distance(endpoint,points.first())<20 -> index to points
                    Geo.distance(endpoint,points.last())<20 -> index to points.reversed();else->null }
            }
            val next=possible.singleOrNull() ?: return null
            result.addAll(if(result.isEmpty()) next.second else next.second.drop(1))
            endpoint=next.second.last();geometries.removeAt(next.first)
        }
        if(Geo.distance(endpoint,to)>20 || Geo.distance(from,to)<100 || result.size>10_000) return null
        AverageSpeedSection("relation/${relation.getLong("id")}",result,ways.mapTo(hashSetOf()) { "way/${it.getLong("ref")}" },
            SpeedLimits.mph(tags.keys().asSequence().associateWith { tags.getString(it) }))
    }.getOrNull()
}

/** No average is invented from straight-line GPS distances or an incomplete section. */
class AverageSectionTracker {
    private var active: AverageSpeedSection?=null
    fun update(fix: Fix,road: RoadMatch?,sections: List<AverageSpeedSection>): ActiveAverageSection? {
        if(fix.accuracyM>25 || fix.bearing==null) return active?.let { ActiveAverageSection(it,Double.NaN) }
        val candidates=if(active!=null) listOf(active!!) else sections
        for(section in candidates) {
            val projection=AverageSpeedSections.projection(fix.point,section.points)
            val heading=projection.second
            if(road==null || road.confidence<.55 || road.road.id !in section.wayIds || projection.first>20 ||
                heading==null || Geo.difference(fix.bearing,heading)>50) continue
            if(active==null && Geo.distance(fix.point,section.points.first())>150) continue // Must observe entry, not guess mid-section.
            active=section
            if(projection.third>.985 && Geo.distance(fix.point,section.points.last())<25) { active=null;return null }
            val length=section.points.zipWithNext().sumOf { Geo.distance(it.first,it.second) }
            return ActiveAverageSection(section,(length*(1-projection.third)).coerceAtLeast(0.0))
        }
        if(active!=null && Geo.projection(fix.point,active!!.points).first>80) active=null
        return active?.let { ActiveAverageSection(it,Double.NaN) }
    }
}
