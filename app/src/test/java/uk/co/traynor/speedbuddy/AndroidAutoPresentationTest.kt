package uk.co.traynor.speedbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAutoPresentationTest {
    @Test fun `confirmed driving state remains compact and authoritative`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.8, limitMph=30))
        assertEquals("30 mph", view.hero); assertEquals("27 mph", view.speed); assertNull(view.status)
    }
    @Test fun `assumed limit is always visibly marked`() {
        val view = AndroidAutoPresenter.present(DriveState(active=true, speedMph=27.0, limitMph=30, limitDecision=LimitDecision(30, reason="continuity", assumed=true)))
        assertEquals("30 mph ⚠", view.hero); assertEquals("Assumed — not confirmed", view.status)
    }
    @Test fun `car surface does not invent a limit when phone is idle`() {
        val view = AndroidAutoPresenter.present(DriveState())
        assertEquals("Limit unavailable", view.hero); assertEquals("Open Speed Buddy on your phone and start driving", view.status)
    }
}
