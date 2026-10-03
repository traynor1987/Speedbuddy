package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class CombinedOwnerLimitsTest {
    private val p=GeoPoint(53.5,-2.8)
    private val road=Road("way/owner", "Owner road", listOf(p,Geo.ahead(p,0.0,500.0)),mapOf("maxspeed" to "30 mph"))
    @Test fun mapUnknownIsExplicitOwnerEvidenceAndDoesNotInheritSource() {
        val correction=RoadLimitCorrection(road.id,RoadLimitKind.UNKNOWN,null,"30 mph",1)
        val selected=OwnerRoadLimits.select(road,0.0,emptyList(),mapOf(road.id to correction))!!
        val result=LimitDecisionEngine().decide(Fix(p,5.0,10.0,1.0,0.0,1000),RoadMatch(road,0.0,0.0,.95),
            30,selected.mph,emptyList(),1000)
        assertNull(result.mph);assertTrue(result.ownerApplied);assertFalse(result.assumed)
        assertEquals("30 mph",road.tags["maxspeed"])
    }
    @Test fun directedPickerCorrectionWinsOnlyInItsTravelDirection() {
        val global=RoadLimitCorrection(road.id,RoadLimitKind.NUMERIC,40,"30 mph",1)
        val directed=listOf(RoadDb.Override(road.id,0.0,20))
        assertEquals(20,OwnerRoadLimits.select(road,0.0,directed,mapOf(road.id to global))!!.mph)
        assertEquals(40,OwnerRoadLimits.select(road,180.0,directed,mapOf(road.id to global))!!.mph)
        assertNull(OwnerRoadLimits.select(road.copy(id="way/other"),0.0,directed,mapOf(road.id to global)))
    }
    @Test fun mapNationalCorrectionRetainsItsExplicitCarriagewayChoice() {
        val global=RoadLimitCorrection(road.id,RoadLimitKind.NATIONAL_DUAL,70,null,1)
        val selection=OwnerRoadLimits.select(road,0.0,emptyList(),mapOf(road.id to global))!!
        assertEquals(70,selection.mph);assertTrue(selection.national)
    }
}
