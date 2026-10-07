package uk.co.traynor.speedbuddy

/** One policy decision for handset, Auto and replay; providers supply facts, never UI text. */
internal enum class RoadSource { LOCAL_OWNER, REGIONAL_PACK, LEGACY_CACHE, LIVE, OVERPASS, UNAVAILABLE }
internal data class ResolvedRoadLimit(val source: RoadSource, val state: RoadProviderState, val limitMph: Int?, val overpassAllowed: Boolean)
internal object RoadProviderResolver {
    fun resolve(owner: Int?, pack: Int?, cached: Int?, live: LiveRoadState?): ResolvedRoadLimit {
        if(owner != null) return ResolvedRoadLimit(RoadSource.LOCAL_OWNER,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,owner,false)
        if(pack != null) return ResolvedRoadLimit(RoadSource.REGIONAL_PACK,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,pack,false)
        if(cached != null) return ResolvedRoadLimit(RoadSource.LEGACY_CACHE,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,cached,false)
        val result=live ?: return ResolvedRoadLimit(RoadSource.UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE,null,false)
        return ResolvedRoadLimit(RoadSource.LIVE,result.state,result.limitMph,result.fallbackAllowed && result.state.permitsOverpass)
    }
}
