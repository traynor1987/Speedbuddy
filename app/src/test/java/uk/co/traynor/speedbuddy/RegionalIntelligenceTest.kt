package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class RegionalIntelligenceTest {
    private val p=GeoPoint(53.5,-2.8)
    private fun road(id: String, points: List<GeoPoint>, tags: Map<String,String>)=Road(id,"Test Road",points,tags)
    private val line=listOf(p,Geo.ahead(p,0.0,500.0))
    private fun limit(tags: Map<String,String>, bearing: Double?=0.0): Int? {
        val r=road("way/1",line,tags)
        return PackSpeedLimits.mph(tags,bearing,RoadMatch(r,0.0,0.0,.95,0.0))
    }
    @Test fun regionalAndLegacyNationalTagsHaveIdenticalMeaning() {
        for((tag,mph) in listOf("GB:nsl_single" to 60,"GB:nsl_dual" to 70,"GB:motorway" to 70,"GB:nsl_restricted" to 30)) {
            for(key in listOf("maxspeed","maxspeed:type","source:maxspeed")) {
                val tags=mapOf(key to tag)
                assertEquals(mph,limit(tags));assertEquals(mph,SpeedLimits.mph(tags))
            }
        }
        assertNull(limit(mapOf("highway" to "motorway")))
        assertNull(limit(mapOf("maxspeed" to "invalid","maxspeed:type" to "GB:motorway")))
    }
    @Test fun directionalNationalAndConditionalTagsNeverBorrowTheOtherDirection() {
        val tags=mapOf("maxspeed:forward" to "GB:nsl_single","maxspeed:backward" to "20 mph")
        assertEquals(60,limit(tags));assertEquals(20,limit(tags,180.0));assertNull(limit(tags,null))
        assertNull(limit(tags+mapOf("maxspeed:forward:conditional" to "30 mph @ (wet)")))
        assertNull(limit(mapOf("maxspeed" to "30 mph","maxspeed:lanes:forward" to "20|30")))
    }
    @Test fun upcomingContinuationUsesItsOutgoingGeometryInBothDirections() {
        val junction=Geo.ahead(p,0.0,100.0)
        val current=road("way/1",listOf(p,junction),mapOf("maxspeed" to "30 mph"))
        val next=road("way/2",listOf(Geo.ahead(junction,0.0,300.0),junction),
            mapOf("maxspeed:forward" to "20 mph","maxspeed:backward" to "50 mph"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        assertEquals(50,UpcomingLimitDetector().detect(fix,RoadMatch(current,0.0,0.0,.95,0.0),30,listOf(current,next))?.mph)
        assertNull(UpcomingLimitDetector().detect(fix,RoadMatch(current,0.0,0.0,.95,0.0),30,
            listOf(current,next.copy(tags=next.tags+mapOf("oneway" to "yes")))))
    }
    @Test fun reverseOneWayTurnSelectsOnlyThePermittedOutgoingLimit() {
        val junction=Geo.ahead(p,0.0,100.0)
        val current=road("way/1",listOf(p,junction),mapOf("maxspeed" to "30 mph"))
        val right=road("way/2",listOf(Geo.ahead(junction,90.0,300.0),junction),
            mapOf("oneway" to "-1","maxspeed:forward" to "20 mph","maxspeed:backward" to "50 mph"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val turns=TurnLimitDetector().detect(fix,RoadMatch(current,0.0,0.0,.95,0.0),30,listOf(current,right))
        assertEquals(50,turns.single().mph);assertEquals(TurnDirection.RIGHT,turns.single().direction)
        assertTrue(TurnLimitDetector().detect(fix,RoadMatch(current,0.0,0.0,.95,0.0),30,
            listOf(current,right.copy(points=right.points.reversed()))).isEmpty())
    }
    @Test fun lookAheadFollowsShortSameLimitSegmentsAndStopsAtABranch() {
        val j=Geo.ahead(p,0.0,70.0)
        val k=Geo.ahead(j,0.0,50.0)
        val current=road("way/1",listOf(p,j),mapOf("maxspeed" to "30 mph"))
        val middle=road("way/2",listOf(j,k),mapOf("maxspeed" to "30 mph"))
        val next=road("way/3",listOf(k,Geo.ahead(k,0.0,300.0)),mapOf("maxspeed" to "GB:nsl_single"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val match=RoadMatch(current,0.0,0.0,.95,0.0)
        val result=UpcomingLimitDetector().detect(fix,match,30,listOf(current,middle,next))!!
        assertEquals(60,result.mph);assertTrue(result.national);assertEquals(120.0,result.distanceM,1.0)
        assertNull(UpcomingLimitDetector().detect(fix,match,30,listOf(current,middle,next,
            next.copy(id="way/4",points=listOf(k,Geo.ahead(k,15.0,300.0))))))
    }
    @Test fun shortCurrentRemainderDoesNotHideADistantSpeedChange() {
        val j=Geo.ahead(p,0.0,10.0)
        val k=Geo.ahead(j,0.0,90.0)
        val current=road("way/1",listOf(p,j),mapOf("maxspeed" to "30 mph"))
        val middle=road("way/2",listOf(j,k),mapOf("maxspeed" to "30 mph"))
        val next=road("way/3",listOf(k,Geo.ahead(k,0.0,300.0)),mapOf("maxspeed" to "50 mph"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        val match=RoadMatch(current,0.0,0.0,.95,0.0)
        val result=UpcomingLimitDetector().detect(fix,match,30,listOf(current,middle,next))!!
        assertEquals(50,result.mph);assertEquals(100.0,result.distanceM,1.0)
        assertNull(UpcomingLimitDetector().detect(fix,match,30,listOf(current,middle.copy(tags=next.tags))))
    }
    @Test fun restrictedThirtyIsNumericRatherThanANationalLimitSign() {
        val r=road("way/1",line,mapOf("maxspeed" to "GB:nsl_restricted"))
        assertEquals(30,limit(r.tags))
        assertFalse(PackSpeedLimits.national(r.tags,0.0,RoadMatch(r,0.0,0.0,.95,0.0)))
    }
    @Test fun directionalLaneFactsPreventDefaultCorrectionSharing() {
        val r=road("way/1",line,mapOf("highway" to "residential","maxspeed" to "30 mph","maxspeed:lanes:backward" to "20|30"))
        assertFalse(CorrectionDirectionPolicy.ordinaryTwoWay(r))
    }
    @Test fun weakCurrentIdentityDoesNotOfferAnAuthoritativeTurnLimit() {
        val j=Geo.ahead(p,0.0,100.0)
        val current=road("way/1",listOf(p,j),mapOf("maxspeed" to "30 mph"))
        val right=road("way/2",listOf(j,Geo.ahead(j,90.0,300.0)),mapOf("maxspeed" to "20 mph"))
        val fix=Fix(p,5.0,10.0,1.0,0.0,1000)
        assertTrue(TurnLimitDetector().detect(fix,RoadMatch(current,0.0,0.0,.5,0.0),30,listOf(current,right)).isEmpty())
    }
}
