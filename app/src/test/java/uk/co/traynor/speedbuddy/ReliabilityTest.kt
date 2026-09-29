package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class ReliabilityTest {
    private val camera=Camera("repeat",GeoPoint(53.005,-2.0),CameraType.SPEED,CameraSource.USER)
    private fun fix(lat: Double,t:Long,bearing:Double=0.0)=Fix(GeoPoint(lat,-2.0),5.0,10.0,1.0,bearing,t)
    @Test fun secondEncounterRearmsOnlyAfterLeavingArea() {
        val detector=CameraApproachDetector()
        assertEquals("New approach",detector.evaluate(fix(53.003,1000),null,listOf(camera),22.0).second.reason)
        assertNull(detector.evaluate(fix(53.0052,2000),null,listOf(camera),22.0).first)
        detector.evaluate(fix(53.015,3000),null,emptyList(),22.0)
        assertEquals("New approach",detector.evaluate(fix(53.003,60000),null,listOf(camera),22.0).second.reason)
    }
    @Test fun duplicateSourcesShareHideAndCorrection() {
        val osm=camera.copy(id="node/1",source=CameraSource.OSM)
        val imported=camera.copy(id="lufop:1",source=CameraSource.LUFOP)
        val aliases=listOf(osm.id to imported.id)
        assertTrue(CameraLayers.merge(listOf(osm,imported),emptyList(),emptyList(),setOf(imported.id),aliases).isEmpty())
        assertTrue(CameraLayers.merge(listOf(imported),emptyList(),emptyList(),setOf(osm.id),aliases).isEmpty())
        val correction=CameraCorrection(imported.id,CameraSource.LUFOP,imported.point,CameraType.RED_LIGHT,90.0,null,null,imported.point)
        val result=CameraLayers.merge(listOf(osm),emptyList(),listOf(correction),emptySet(),aliases).single()
        assertEquals(CameraType.RED_LIGHT,result.type);assertEquals(90.0,result.direction!!,0.0)
    }
    @Test fun blankOverrideClearsSourceSpeed() {
        val source=camera.copy(source=CameraSource.OSM,enforcedMph=30)
        assertNull(CameraCorrection(source.id,source.source,source.point,source.type,null,null).apply(source).enforcedMph)
    }
    @Test fun variableAndDirectionalRoadsCannotBecomeFixedThroughAnOverride() {
        for(tag in listOf("maxspeed:variable","maxspeed:conditional","maxspeed:lanes","maxspeed:forward")) {
            val road=Road("way/1",null,listOf(camera.point,GeoPoint(53.006,-2.0)),mapOf("maxspeed" to "30 mph",tag to "yes"))
            assertNull(SpeedLimits.mph(RoadLimitCorrection(road.id,RoadLimitKind.NUMERIC,20,"30 mph",0).apply(road).tags))
        }
    }
    @Test fun enforcedBearingComesFromRelationGeometry() {
        assertNull(OsmEnforcementDirection.travel(JSONArray("[]")))
        assertEquals(0.0,OsmEnforcementDirection.travel(JSONArray("""[{"type":"node","role":"from","lat":53.0,"lon":-2.0},{"type":"node","role":"device","lat":53.001,"lon":-2.0}]"""))!!,.1)
    }
    @Test fun unknownGapsAndSameLimitDoNotRepeatVoice() {
        val gate=LimitChangeGate()
        assertNull(gate.update(30));assertNull(gate.update(null));assertNull(gate.update(30))
        assertEquals(20,gate.update(20));assertNull(gate.update(20));assertNull(gate.update(null));assertEquals(40,gate.update(40))
    }
    @Test fun boundedReadersRejectBeforeReadingUnlimitedContent() {
        assertEquals("abc",BoundedIo.text("abc".byteInputStream(),3))
        assertThrows(IllegalArgumentException::class.java) { BoundedIo.text("abcdef".byteInputStream(),3) }
    }
    @Test fun visitedRoadDataRemainsUsableOfflineWithAnAgeLimit() {
        val snapshot=OsmSnapshot(camera.point,1000,emptyList(),emptyList())
        assertTrue(snapshot.usable(camera.point,1000+2*86_400_000L))
        assertFalse(snapshot.usable(camera.point,1000+31*86_400_000L))
    }
    @Test fun feedMustBeHttpsWithoutEmbeddedCredentials() {
        assertTrue(CameraDatabaseUpdates.validFeed("https://example.com/cameras.zip"))
        assertFalse(CameraDatabaseUpdates.validFeed("http://example.com/cameras.zip"))
        assertFalse(CameraDatabaseUpdates.validFeed("https://owner:secret@example.com/cameras.zip"))
    }
    @Test fun sectionRequiresCompleteConnectedGeometryAndUsesDistanceProgress() {
        val relation=org.json.JSONObject("""{"type":"relation","id":1,"tags":{"enforcement":"average_speed","maxspeed":"30 mph"},"members":[{"type":"node","role":"from","lat":53.0,"lon":-2.0},{"type":"node","role":"to","lat":53.01,"lon":-2.0},{"type":"way","role":"section","ref":1,"geometry":[{"lat":53.0,"lon":-2.0},{"lat":53.001,"lon":-2.0},{"lat":53.01,"lon":-2.0}]}]}""")
        val section=AverageSpeedSections.parse(relation)!!
        assertEquals(30,section.mph)
        assertEquals(.1,AverageSpeedSections.projection(GeoPoint(53.001,-2.0),section.points).third,.01)
        relation.getJSONArray("members").getJSONObject(2).getJSONArray("geometry").getJSONObject(2).put("lon",-3.0)
        assertNull(AverageSpeedSections.parse(relation))
    }
    @Test fun parallelCarriagewayUsesActualRoadGeometry() {
        val road=Road("way/current",null,listOf(GeoPoint(53.0,-2.0),GeoPoint(53.01,-2.0)),emptyMap())
        val other=road.copy(id="way/other",points=listOf(GeoPoint(53.0,-1.9997),GeoPoint(53.01,-1.9997)))
        val opposite=camera.copy(point=GeoPoint(53.005,-1.9997))
        val match=RoadMatch(road,0.0,0.0,.8)
        val decision=CameraApproachDetector().evaluate(fix(53.003,1000),match,listOf(opposite),22.0,listOf(road,other)).second
        assertEquals("Different carriageway",decision.reason)
    }

}
