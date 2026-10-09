package uk.co.traynor.speedbuddy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.Before

class DriveBusTest {
    @Before fun resetBefore()=DriveBus.set(DriveState())
    @After fun resetBus() = DriveBus.set(DriveState())

    @Test fun `successive location speeds publish immediately without waiting for road processing`() {
        val fix = Fix(GeoPoint(53.4, -2.8), 4.0, 10.0, 1.0, 90.0, 1_000L)
        DriveBus.set(DriveState(active=true, fix=fix, road=RoadMatch(Road("way/1",null,listOf(fix.point,Geo.ahead(fix.point,90.0,500.0)),emptyMap()),0.0,0.0,.95),
            limitMph=30, roadDecisionElapsedMs=1_000L, status="Saved road data ready"))
        DriveBus.publishLocationSpeed(22.0, fix.copy(elapsedMs=1_001L))
        val first = DriveBus.state.value
        DriveBus.publishLocationSpeed(28.0, fix.copy(elapsedMs=2_000L))
        val second = DriveBus.state.value
        assertEquals(22.0, first.speedMph)
        assertEquals(28.0, second.speedMph)
        assertNotEquals(first, second)
        assertEquals(30, second.limitMph)
    }
    @Test fun `late road result and duplicate GPS cannot replace newer speed or point`() {
        val old=Fix(GeoPoint(53.4,-2.8),5.0,10.0,1.0,0.0,1000)
        val newest=old.copy(point=Geo.ahead(old.point,90.0,50.0),elapsedMs=2000)
        DriveBus.set(DriveState(active=true,fix=old,speedMph=20.0))
        DriveBus.publishLocationSpeed(28.0,newest)
        DriveBus.set(DriveState(active=true,fix=old,speedMph=20.0,limitMph=30))
        DriveBus.publishLocationSpeed(19.0,old)
        assertEquals(newest,DriveBus.state.value.fix);assertEquals(28.0,DriveBus.state.value.speedMph)
        val delayed=DriveLimitResult(old,null,30,LimitDecision(30,reason="old"),null)
        assertEquals(DriveBus.state.value,delayed.applyTo(DriveBus.state.value))
    }
    @Test fun `pending old limit expires and cannot be used for new-position alerts`() {
        val fix=Fix(GeoPoint(53.4,-2.8),5.0,10.0,1.0,0.0,1000)
        DriveBus.set(DriveState(active=true,fix=fix,road=RoadMatch(Road("way/1",null,listOf(fix.point,Geo.ahead(fix.point,0.0,500.0)),emptyMap()),0.0,0.0,.95),limitMph=30,roadDecisionElapsedMs=1000,
            limitDecision=LimitDecision(30,reason="matched"),overspeed=true))
        DriveBus.publishLocationSpeed(25.0,fix.copy(elapsedMs=2000))
        assertEquals(30,DriveBus.state.value.limitMph);assertEquals(true,DriveBus.state.value.limitDecision?.assumed)
        assertEquals(false,DriveBus.state.value.overspeed)
        DriveBus.publishLocationSpeed(25.0,fix.copy(elapsedMs=4000))
        assertEquals(null,DriveBus.state.value.limitMph)
    }
}
