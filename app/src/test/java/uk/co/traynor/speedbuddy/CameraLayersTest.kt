package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class CameraLayersTest {
    private val source = Camera("lufop:a", GeoPoint(53.0, -2.0), CameraType.SPEED, CameraSource.LUFOP)
    private val owner = Camera("owner:a", GeoPoint(53.001, -2.0), CameraType.RED_LIGHT, CameraSource.USER)

    @Test fun mergesOwnerAndSourceWithoutMutatingTheSource() {
        val correction = CameraCorrection(source.id, source.source, GeoPoint(53.002, -2.0),
            CameraType.RED_LIGHT, 90.0, "Eastbound", 20)
        val effective = CameraLayers.merge(listOf(source), listOf(owner), listOf(correction), emptySet())
        assertEquals(2, effective.size)
        assertEquals(source.point, GeoPoint(53.0, -2.0))
        assertEquals(correction.point, effective.first { it.id == source.id }.point)
        assertEquals(20, effective.first { it.id == source.id }.enforcedMph)
        assertEquals(owner, effective.first { it.id == owner.id })
    }

    @Test fun suppressionOnlyAffectsSourceRecordAndSurvivesReplacement() {
        val hidden = setOf(source.id)
        assertEquals(listOf(owner), CameraLayers.merge(listOf(source), listOf(owner), emptyList(), hidden))
        assertEquals(listOf(owner), CameraLayers.merge(listOf(source.copy(point = GeoPoint(53.003, -2.0))),
            listOf(owner), emptyList(), hidden))
    }

    @Test fun wrongSourceCorrectionCannotAlterUnrelatedRecord() {
        val correction = CameraCorrection(source.id, CameraSource.OSM, source.point,
            CameraType.RED_LIGHT, null, null)
        assertEquals(source, CameraLayers.merge(listOf(source), emptyList(), listOf(correction), emptySet()).single())
    }
    @Test fun sourceRefreshReconcilesOnlyOnePlausibleNearbyRecord() {
        val moved = source.copy(id = "lufop:b", point = GeoPoint(53.00004, -2.0))
        assertEquals(moved.id, CameraLayers.reconcileId(source, listOf(moved)))
        assertNull(CameraLayers.reconcileId(source, listOf(moved,
            moved.copy(id = "lufop:c", point = GeoPoint(53.00005, -2.0)))))
        assertNull(CameraLayers.reconcileId(source, listOf(moved.copy(point = GeoPoint(53.01, -2.0)))))
    }
}
