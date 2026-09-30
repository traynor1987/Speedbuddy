package uk.co.traynor.speedbuddy

import org.json.JSONArray

/** OSM direction=* is camera-facing direction, not necessarily enforced travel direction. */
object OsmEnforcementDirection {
    fun travel(members: JSONArray): Double? {
        fun point(role: String): GeoPoint? {
            val items = (0 until members.length()).map { members.getJSONObject(it) }
                .filter { it.optString("role") == role && it.optString("type") == "node" && it.has("lat") && it.has("lon") }
            return items.singleOrNull()?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
        }
        val from = point("from") ?: return null
        val to = point("to") ?: point("device") ?: return null
        return if (Geo.distance(from, to) in 5.0..1000.0) Geo.bearing(from, to) else null
    }
}
