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
    @Test fun ordinaryTwoWaySignTapAppliesBothWaysButNeverToAnotherWay() {
        val ordinary=road.copy(tags=road.tags + ("highway" to "residential"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val row=QuickLimitCorrection.capture(fix,RoadMatch(ordinary,0.0,0.0,.95),30,20,ordinary.id,1000)!!
        assertEquals(20,OwnerRoadLimits.select(ordinary,180.0,listOf(row),emptyMap(),p)?.mph)
        assertNull(OwnerRoadLimits.select(ordinary.copy(id="way/other"),180.0,listOf(row),emptyMap(),p))
        assertNull(OwnerRoadLimits.select(ordinary,180.0,listOf(row),emptyMap(),Geo.ahead(p,0.0,200.0)))
    }
    @Test fun existingRegionalAliasCorrectionRemainsUsableOnLegacyWayIdentity() {
        assertEquals(20,OwnerRoadLimits.select(road.copy(id="way/123"),0.0,
            listOf(RoadDb.Override("osm:123",0.0,20)),emptyMap(),p)?.mph)
        assertNull(OwnerRoadLimits.select(road.copy(id="way/123"),180.0,
            listOf(RoadDb.Override("osm:123",0.0,20)),emptyMap(),p))
    }
    @Test fun exceptionsStayDirectionalAndSavedExplicitValueWinsItsOwnDirection() {
        val ordinary=road.copy(tags=road.tags + ("highway" to "residential"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        fun capture(r: Road,explicit: Boolean=false)=QuickLimitCorrection.capture(fix,RoadMatch(r,0.0,0.0,.95),30,20,r.id,1000,explicit)!!
        for(r in listOf(ordinary.copy(tags=ordinary.tags + ("oneway" to "yes")),
            ordinary.copy(tags=ordinary.tags + ("maxspeed:backward" to "50 mph")),
            ordinary.copy(tags=ordinary.tags + ("highway" to "primary_link")))) {
            assertFalse(capture(r).sharedAcrossDirections)
            assertNull(OwnerRoadLimits.select(r,180.0,listOf(capture(r)),emptyMap(),p))
        }
        assertFalse(capture(ordinary,true).sharedAcrossDirections)
        val shared=capture(ordinary)
        val existing=RoadDb.Override(ordinary.id,180.0,50)
        assertEquals(20,OwnerRoadLimits.select(ordinary,0.0,listOf(shared,existing),emptyMap(),p)?.mph)
        assertEquals(50,OwnerRoadLimits.select(ordinary,180.0,listOf(shared,existing),emptyMap(),p)?.mph)
        // A later pack with directional source tags cannot expand an old shared tap.
        val directional=ordinary.copy(tags=ordinary.tags + ("maxspeed:backward" to "50 mph"))
        assertNull(OwnerRoadLimits.select(directional,180.0,listOf(shared),emptyMap(),p))
    }
    @Test fun sharedOverrideJsonRoundTripAndLegacyDirectionArePreserved() {
        val shared=RoadDb.Override("osm:123",0.0,20,sharedAcrossDirections=true)
        assertEquals(shared,RoadJson.decodeOverride(RoadJson.override(shared)))
        val old=org.json.JSONObject().put("road","way/123").put("bearing",0).put("mph",30)
        assertFalse(RoadJson.decodeOverride(old).sharedAcrossDirections)
    }
}
