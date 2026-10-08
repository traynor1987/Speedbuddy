package uk.co.traynor.speedbuddy

/** Public cache compatibility policy; owner evidence remains in its separate stores. */
internal object DrivingCacheFallback {
    fun needsRegion(completeTile: Boolean, local: LocalRoads): Boolean = !completeTile || local.roads.isEmpty()
}

/** Public OSM tiles are a last-resort cache; a covered verified pack must not refresh them. */
internal object RegionalRefreshPolicy {
    fun permitsLegacyRefresh(state: RoadProviderState) = state in setOf(
        RoadProviderState.COVERAGE_UNAVAILABLE, RoadProviderState.SERVICE_UNAVAILABLE)
}

/** The driving service and tests use this same offline cache reconciliation path. */
internal class DrivingRoadRepository(private val roads: RoadDb,
    private val savedRegion: (GeoPoint)->OsmSnapshot?) {
    private var lastImport: Triple<RoadTile,GeoPoint,Long>? = null
    fun nearby(point: GeoPoint, completeTile: Boolean): LocalRoads {
        var local=roads.nearby(point)
        if(DrivingCacheFallback.needsRegion(completeTile,local)) {
            savedRegion(point)?.takeIf { it.usable(point,System.currentTimeMillis()) }?.let { region ->
                val key=Triple(RoadTile.at(point),region.center,region.fetchedAt)
                if(key!=lastImport || local.roads.isEmpty()) {
                    // A failed optional legacy import must not discard already readable tiled data.
                    runCatching { roads.mergeMapSnapshot(region) }.onSuccess { lastImport=key }
                }
                local=roads.nearby(point)
            }
        }
        return local
    }
}
