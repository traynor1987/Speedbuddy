package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class DrivingCacheFallbackTest {
    private val point=GeoPoint(53.5,-2.8)
    private val unrelated=Road("way/unrelated",null,listOf(point,Geo.ahead(point,0.0,100.0)),emptyMap())
    @Test fun partialTileWithOtherRoadsStillConsultsSavedMapCoverage() {
        assertTrue(DrivingCacheFallback.needsRegion(false,LocalRoads(listOf(SavedRoad(unrelated,2000)),emptyList())))
    }
    @Test fun completeTileWithLocalRoadsAvoidsOlderRegionalFallback() {
        assertFalse(DrivingCacheFallback.needsRegion(true,LocalRoads(listOf(SavedRoad(unrelated,2000)),emptyList())))
    }
    @Test fun verifiedRegionalCoverageSuppressesUnrelatedLegacyTileRefreshes() {
        assertFalse(RegionalRefreshPolicy.permitsLegacyRefresh(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN))
        assertFalse(RegionalRefreshPolicy.permitsLegacyRefresh(RoadProviderState.ROAD_MATCHED_LIMIT_UNKNOWN))
        assertFalse(RegionalRefreshPolicy.permitsLegacyRefresh(RoadProviderState.ROAD_MATCH_UNCERTAIN))
        assertTrue(RegionalRefreshPolicy.permitsLegacyRefresh(RoadProviderState.COVERAGE_UNAVAILABLE))
        assertTrue(RegionalRefreshPolicy.permitsLegacyRefresh(RoadProviderState.SERVICE_UNAVAILABLE))
    }
}
