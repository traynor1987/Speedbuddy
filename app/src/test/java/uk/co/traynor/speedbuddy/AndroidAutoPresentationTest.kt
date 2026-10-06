package uk.co.traynor.speedbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoPresentationTest {
    @Test fun `confirmed road limit selects its UK sign and keeps GPS speed secondary`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.8, limitMph=30))
        assertEquals(AndroidAutoLimitSign.MPH_30, view.limitSign)
        assertEquals("30 mph", view.limitLabel)
        assertEquals("27 mph", view.speed)
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
        assertEquals("22 mph", view.speed)
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
    @Test fun `car surface does not invent a limit when phone is idle`() {
        val view = AndroidAutoPresenter.present(DriveState())
        assertEquals(AndroidAutoLimitSign.UNKNOWN, view.limitSign)
        assertEquals("Open Speed Buddy on your phone and start driving", view.status)
        assertFalse(view.limitLabel.contains("unavailable", ignoreCase=true))
    }
}
