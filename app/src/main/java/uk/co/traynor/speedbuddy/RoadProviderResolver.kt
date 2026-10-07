package uk.co.traynor.speedbuddy

/** One policy decision for handset, Auto and replay; providers supply facts, never UI text. */
internal enum class RoadSource { LOCAL_OWNER, REGIONAL_PACK, LEGACY_CACHE, LIVE, OVERPASS, UNAVAILABLE }
internal data class ResolvedRoadLimit(val source: RoadSource, val state: RoadProviderState, val limitMph: Int?, val overpassAllowed: Boolean)
internal object RoadProviderResolver {
    /**
     * Resolves the released provider hierarchy without turning Unknown or
     * uncertain regional evidence into a hunt for a convenient numeric value.
     */
    fun resolveProviderStates(owner: Int?, regional: LiveRoadState?, cached: Int?, live: LiveRoadState?): ResolvedRoadLimit {
        if(owner != null) return ResolvedRoadLimit(RoadSource.LOCAL_OWNER,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,owner,false)
        if(regional != null && regional.state !in setOf(RoadProviderState.COVERAGE_UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE))
            return ResolvedRoadLimit(RoadSource.REGIONAL_PACK,regional.state,regional.limitMph,false)
        if(cached != null) return ResolvedRoadLimit(RoadSource.LEGACY_CACHE,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,cached,false)
        val result=live ?: regional ?: return ResolvedRoadLimit(RoadSource.UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE,null,false)
        return ResolvedRoadLimit(RoadSource.LIVE,result.state,result.limitMph,result.fallbackAllowed && result.state.permitsOverpass)
    }
    fun resolve(owner: Int?, pack: Int?, cached: Int?, live: LiveRoadState?): ResolvedRoadLimit {
        if(owner != null) return ResolvedRoadLimit(RoadSource.LOCAL_OWNER,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,owner,false)
        if(pack != null) return ResolvedRoadLimit(RoadSource.REGIONAL_PACK,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,pack,false)
        if(cached != null) return ResolvedRoadLimit(RoadSource.LEGACY_CACHE,RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,cached,false)
        val result=live ?: return ResolvedRoadLimit(RoadSource.UNAVAILABLE,RoadProviderState.SERVICE_UNAVAILABLE,null,false)
        return ResolvedRoadLimit(RoadSource.LIVE,result.state,result.limitMph,result.fallbackAllowed && result.state.permitsOverpass)
    }
}
