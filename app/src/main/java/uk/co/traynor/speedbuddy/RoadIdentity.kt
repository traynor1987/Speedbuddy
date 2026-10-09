package uk.co.traynor.speedbuddy

/** Only OSM numeric way aliases are interchangeable; names never establish identity. */
internal object RoadIdentity {
    private val alias = Regex("^(?:way/|osm:)([1-9][0-9]*)$")
    fun canonical(id: String): String = alias.matchEntire(id)
        ?.let { "way/${it.groupValues[1]}" } ?: id
    fun same(a: String, b: String) = canonical(a) == canonical(b)
    fun road(road: Road): Road {
        val id=canonical(road.id)
        return if(id==road.id) road else road.copy(id=id)
    }
    fun boundary(b: BoundaryCorrection) = b.copy(fromId=canonical(b.fromId),toId=canonical(b.toId),viaIds=b.viaIds.map(::canonical))
    fun observation(o: BoundaryObservation) = o.copy(from=road(o.from),to=road(o.to))
}

/** Default sharing is conservative and remains confined to the same identified way. */
internal object CorrectionDirectionPolicy {
    fun ordinaryTwoWay(road: Road): Boolean = road.tags["highway"] in setOf(
        "residential", "unclassified", "tertiary", "secondary", "primary", "trunk", "living_street") &&
        road.tags["oneway"]?.lowercase() in setOf(null,"no","0","false") &&
        road.tags["junction"] !in setOf("roundabout","circular") &&
        road.tags.keys.none { it.startsWith("maxspeed") && (":forward" in it || ":backward" in it) }
}

/** OSM forward/backward refer to node order, independent of absolute compass heading. */
internal enum class WayTravelDirection {
    FORWARD, BACKWARD;
    companion object {
        fun from(match: RoadMatch, bearing: Double?): WayTravelDirection? {
            val heading=match.wayHeading ?: return null
            if(bearing==null || !bearing.isFinite()) return null
            return when {
                Geo.difference(bearing,heading)<=45 -> FORWARD
                Geo.difference(bearing,(heading+180)%360)<=45 -> BACKWARD
                else -> null
            }
        }
    }
}
