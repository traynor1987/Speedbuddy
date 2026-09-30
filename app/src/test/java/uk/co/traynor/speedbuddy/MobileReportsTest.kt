package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class MobileReportsTest {
    @Test fun mobileWarningUsesReportedLanguage() {
        val camera=Camera("mobile:test",GeoPoint(53.0,-2.0),CameraType.valueOf("MOBILE"),CameraSource.USER)
        assertEquals("Mobile speed camera reported ahead.",CameraAnnouncement.text(camera,null))
    }
}

class MobileReportPolicyTest {
    private val point=GeoPoint(53.0,-2.0)
    private fun report()=MobileReport("mobile:test",point,1_000L,1_000L,7_201_000L,120,0.0,"way/1","High Street")
    @Test fun expiryIsExclusiveAndFutureTimestampsAreInactive() {
        assertTrue(report().activeAt(7_200_999L));assertFalse(report().activeAt(7_201_000L))
        assertFalse(report().activeAt(999L))
    }
    @Test fun confirmationRenewsLifetimeButNeverResurrectsExpiredReport() {
        val confirmed=report().confirm(5_000L)!!
        assertEquals(1_000L,confirmed.reportedAtMs);assertEquals(5_000L,confirmed.observedAtMs)
        assertEquals(7_205_000L,confirmed.expiresAtMs)
        assertNull(report().confirm(7_201_000L))
    }
    @Test fun captureRejectsStaleWeakFixAndDoesNotInventStationaryHeading() {
        val fix=Fix(point,4.0,10.0,1.0,90.0,1_000L)
        assertNull(MobileReportCapture.create(fix,null,6_001L,10_000L,120))
        assertNull(MobileReportCapture.create(fix.copy(accuracyM=36.0),null,1_000L,10_000L,120))
        assertNull(MobileReportCapture.create(fix.copy(bearing=Double.NaN),null,1_000L,10_000L,120)?.direction)
        assertNull(MobileReportCapture.create(fix.copy(speedMps=0.0),null,1_000L,10_000L,120)?.direction)
        assertEquals(90.0,MobileReportCapture.create(fix,null,1_000L,10_000L,120)!!.direction!!,.01)
    }
    @Test fun captureRetainsOnlyReliableRoadContext() {
        val road=Road("way/1","High Street",listOf(Geo.ahead(point,180.0,300.0),Geo.ahead(point,0.0,300.0)),emptyMap())
        val fix=Fix(point,4.0,10.0,1.0,0.0,1_000L)
        assertEquals("way/1",MobileReportCapture.create(fix,RoadMatch(road,0.0,0.0,.8),1_000L,10_000L,120)!!.roadId)
        assertNull(MobileReportCapture.create(fix,RoadMatch(road,0.0,0.0,.3),1_000L,10_000L,120)!!.roadId)
    }
    @Test fun repeatsReuseOnlyRecentSameDirectionSameRoadReports() {
        assertTrue(report().sameEncounter(report().copy(id="mobile:other",reportedAtMs=2_000L,observedAtMs=2_000L,expiresAtMs=7_202_000L)))
        assertFalse(report().sameEncounter(report().copy(direction=180.0)))
        assertFalse(report().sameEncounter(report().copy(roadId="way/2")))
        assertFalse(report().sameEncounter(report().copy(reportedAtMs=121_001L,observedAtMs=121_001L,expiresAtMs=7_321_001L)))
    }
    @Test fun sharedEngineKeepsStationaryWarningWithoutRepeatingSpeechAndDropsExpiry() {
        val camera=report().asCamera();val detector=CameraApproachDetector()
        val fix=Fix(Geo.ahead(point,180.0,200.0),4.0,10.0,1.0,0.0,1_000L)
        assertEquals("New approach",detector.evaluate(fix,null,listOf(camera),22.0,nowMs=2_000L).second.reason)
        val stopped=detector.evaluate(fix.copy(speedMps=0.0,bearing=null,elapsedMs=2_000L),null,listOf(camera),0.0,nowMs=3_000L)
        assertNotNull(stopped.first);assertEquals("Approach active",stopped.second.reason)
        assertNull(detector.evaluate(fix,null,listOf(camera),22.0,nowMs=7_201_000L).first)
    }
    @Test fun reportsBehindAndOppositeDirectionAreRejected() {
        val camera=report().asCamera();val fix=Fix(Geo.ahead(point,0.0,100.0),4.0,10.0,1.0,0.0,1_000L)
        assertNull(CameraApproachDetector().evaluate(fix,null,listOf(camera),22.0,nowMs=2_000L).first)
        assertNull(CameraApproachDetector().evaluate(fix.copy(bearing=180.0),null,listOf(camera),22.0,nowMs=2_000L).first)
    }
    @Test fun knownReportRoadRejectsNearbyParallelWay() {
        val own=Road("way/2",null,listOf(Geo.ahead(point,180.0,500.0),Geo.ahead(point,0.0,500.0)),emptyMap())
        val other=own.copy(id="way/1",points=own.points.map { Geo.ahead(it,90.0,10.0) })
        val camera=report().copy(point=Geo.ahead(point,90.0,10.0)).asCamera()
        val fix=Fix(Geo.ahead(point,180.0,200.0),4.0,10.0,1.0,0.0,1_000L)
        val decision=CameraApproachDetector().evaluate(fix,RoadMatch(own,0.0,0.0,.9),listOf(camera),22.0,listOf(own,other),2_000L)
        assertNull(decision.first);assertEquals("Different reported road",decision.second.reason)
    }
    @Test fun mobileToggleDoesNotDisableFixedCameras() {
        assertTrue(CameraAlertPolicy.enabled(CameraType.SPEED,true,false,true,true))
        assertFalse(CameraAlertPolicy.enabled(CameraType.MOBILE,true,false,true,true))
        assertFalse(CameraAlertPolicy.enabled(CameraType.SPEED,false,true,true,true))
        assertTrue(CameraAlertPolicy.enabled(CameraType.MOBILE,false,true,false,false))
    }
}
