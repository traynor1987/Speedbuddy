package uk.co.traynor.speedbuddy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DriveBusTest {
    @After fun resetBus() = DriveBus.set(DriveState())

    @Test fun `successive location speeds publish immediately without waiting for road processing`() {
        val fix = Fix(GeoPoint(53.4, -2.8), 4.0, 10.0, 1.0, 90.0, 1_000L)
        DriveBus.set(DriveState(active=true, limitMph=30, status="Saved road data ready"))
        DriveBus.publishLocationSpeed(22.0, fix)
        val first = DriveBus.state.value
        DriveBus.publishLocationSpeed(28.0, fix.copy(elapsedMs=2_000L))
        val second = DriveBus.state.value
        assertEquals(22.0, first.speedMph)
        assertEquals(28.0, second.speedMph)
        assertNotEquals(first, second)
        assertEquals(30, second.limitMph)
    }
}
