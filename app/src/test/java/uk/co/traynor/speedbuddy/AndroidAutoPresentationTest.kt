package uk.co.traynor.speedbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoPresentationTest {
    @Test fun `every supported UK limit selects its matching sign`() {
        mapOf(
            20 to AndroidAutoLimitSign.MPH_20, 30 to AndroidAutoLimitSign.MPH_30,
            40 to AndroidAutoLimitSign.MPH_40, 50 to AndroidAutoLimitSign.MPH_50,
            60 to AndroidAutoLimitSign.MPH_60, 70 to AndroidAutoLimitSign.MPH_70,
        ).forEach { (limit, sign) -> assertEquals(sign, AndroidAutoPresenter.signFor(limit)) }
    }
    @Test fun `confirmed road limit selects its UK sign and keeps GPS speed secondary`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.8, limitMph=30))
        assertEquals(AndroidAutoLimitSign.MPH_30, view.limitSign)
        assertEquals("30 mph", view.limitLabel)
        assertEquals("27 MPH", view.speed)
        assertNull(view.confidence)
    }
    @Test fun `assumed limit is always visibly marked`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.0, limitMph=30, limitDecision=LimitDecision(30, reason="continuity", assumed=true)))
        assertEquals(AndroidAutoLimitSign.MPH_30, view.limitSign)
        assertEquals("⚠ Assumed — not confirmed", view.confidence)
    }
    @Test fun `unknown limit uses neutral sign instead of an unavailable hero`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=22.0))
        assertEquals(AndroidAutoLimitSign.UNKNOWN, view.limitSign)
        assertEquals("—", view.limitLabel)
        assertEquals("22 MPH", view.speed)
    }
    @Test fun `only a different genuinely ahead limit is displayed`() {
        val state = DriveState(active=true, speedMph=27.0, limitMph=30,
            upcoming=UpcomingLimit(40, 164.6, false))
        assertEquals("NEXT 40 mph · 180 yd", AndroidAutoPresenter.present(state).upcoming)
        assertNull(AndroidAutoPresenter.present(state.copy(upcoming=UpcomingLimit(30, 164.6, false))).upcoming)
        assertNull(AndroidAutoPresenter.present(state.copy(upcoming=UpcomingLimit(40, 0.0, false))).upcoming)
        assertNull(AndroidAutoPresenter.present(state.copy(upcoming=UpcomingLimit(40, -4.0, false))).upcoming)
    }
    @Test fun `camera warning takes priority and identifies the camera type and enforcement limit`() {
        val camera=Camera("camera",GeoPoint(53.0,-2.0),CameraType.COMBINED,CameraSource.OSM,enforcedMph=30)
        val view=AndroidAutoPresenter.present(DriveState(active=true,limitMph=30,alert=Alert(camera,320.0)))
        assertEquals("⚠ SPEED + RED-LIGHT CAMERA · 30 mph · 349 yd", view.camera)
        assertTrue(view.camera!!.startsWith("⚠"))
    }
    @Test fun `camera and a credible upcoming limit coexist`() {
        val camera=Camera("camera",GeoPoint(53.0,-2.0),CameraType.SPEED,CameraSource.OSM,enforcedMph=30)
        val view=AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.0, limitMph=30,
            alert=Alert(camera,320.0), upcoming=UpcomingLimit(40,164.6,false)))
        assertEquals("⚠ SPEED CAMERA · 30 mph · 349 yd", view.camera)
        assertEquals("NEXT 40 mph · 180 yd", view.upcoming)
    }
    @Test fun `changing authoritative GPS speeds remain distinct car presentations`() {
        val first=AndroidAutoPresenter.present(DriveState(active=true,speedMph=21.2,limitMph=30))
        val second=AndroidAutoPresenter.present(DriveState(active=true,speedMph=28.9,limitMph=30))
        assertEquals("21 MPH",first.speed)
        assertEquals("28 MPH",second.speed)
        assertFalse(first.speed == second.speed)
    }
    @Test fun `car surface does not invent a limit when phone is idle`() {
        val view = AndroidAutoPresenter.present(DriveState())
        assertEquals(AndroidAutoLimitSign.UNKNOWN, view.limitSign)
        assertEquals("Open Speed Buddy on your phone and start driving", view.status)
        assertFalse(view.limitLabel.contains("unavailable", ignoreCase=true))
    }
}
