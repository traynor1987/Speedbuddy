package uk.co.traynor.speedbuddy


import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test


class PhysicalRoadPipelineTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val p=GeoPoint(53.0,-2.0)
    private fun road(id: String,a: Double,b: Double,mph: Int)=Road(id,"Main",
        listOf(Geo.ahead(p,0.0,a),Geo.ahead(p,0.0,b)),mapOf("highway" to "primary","maxspeed" to "$mph mph"))
    private val old=road("old",0.0,200.0,60)
    private val next=road("next",200.0,1500.0,40)
    private fun fix(m: Double,t: Long,b: Double=0.0)=Fix(Geo.ahead(p,0.0,m),5.0,10.0,1.0,b,t)
    @Test fun driveStateReceivesConfirmedAssumedUpcomingAndUnknownThroughProductionPipeline() {
        val pipeline=DrivingLimitPipeline()
        fun state(m: Double,t: Long,roads: List<Road>)=pipeline.evaluate(fix(m,t),roads,emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t).applyTo(DriveState(active=true))
        assertEquals(60,state(180.0,1000,listOf(old,next)).limitMph)
        val gap=state(190.0,2000,emptyList())
        assertEquals(60,gap.limitMph);assertTrue(gap.limitDecision!!.assumed)
        assertFalse(gap.status.contains("unknown"))
        val candidate=state(225.0,3000,listOf(old,next))
        assertEquals(60,candidate.limitMph);assertEquals(40,candidate.upcoming!!.mph)
        state(240.0,4000,listOf(old,next))
        assertEquals(40,state(260.0,5000,listOf(old,next)).limitMph)
        assertNull(state(900.0,100_000,emptyList()).limitMph)
    }
    @Test fun ordinaryPickerEvidenceSurvivesRestartRefreshAndBackupAndProducesDirectedBoundary() {
        val name="physical-pipeline.db";context.deleteDatabase(name)
        val pipeline=DrivingLimitPipeline()
        for((m,t) in listOf(180.0 to 1000L,225.0 to 2000L,240.0 to 3000L,255.0 to 4000L))
            pipeline.evaluate(fix(m,t),listOf(old,next),emptyList(),emptyMap(),emptyList(),emptyList(),t,10_000+t)
        val match=RoadMatch(next,0.0,0.0,.95)
        val first=pipeline.engine.planSelection(fix(260.0,4500),match,40,60,next.id,4500,14_500,emptyList())!!
        RoadDb(context,name).use { db ->
            db.saveSelection(first,"{\"kind\":\"boundary observation\"}")
            assertTrue(db.overrides().isEmpty())
            db.replace(RoadTileData(RoadTile.at(p),1000,listOf(old,next),emptyList()))
            db.replace(RoadTileData(RoadTile.at(p),2000,listOf(old,next),emptyList()))
            // A directed picker override from the rejected candidate must be retired atomically.
            db.setOverride(next.id,0.0,40)
        }
        RoadDb(context,name).use { db ->
            val observations=db.observations();assertEquals(first.observation,observations.single())
            val restarted=DrivingLimitPipeline()
            val before=restarted.evaluate(fix(285.0,5000),listOf(old,next),db.overrides(),emptyMap(),db.boundaries(),observations,5000,15_000)
            assertEquals(60,before.decision.mph);assertEquals(40,before.upcoming!!.mph)
            val second=restarted.engine.planSelection(fix(300.0,6000),match,40,40,next.id,6000,16_000,observations)!!
            db.saveSelection(second,"{\"kind\":\"boundary correction\"}")
            assertTrue(db.observations().isEmpty());assertTrue(db.overrides().isEmpty())
            restarted.acceptSavedSelection(second)
            val live=restarted.evaluate(fix(300.0,6000),listOf(old,next),db.overrides(),emptyMap(),db.boundaries(),db.observations(),6000,16_000).applyTo(DriveState())
            assertEquals(40,live.limitMph);assertFalse(live.limitDecision!!.assumed)
            val text=OwnerBackupCodec.export(emptyList(),context.getSharedPreferences("physical-pipeline",0),boundaries=db.boundaries(),boundaryObservations=observations)
            val restored=OwnerBackupCodec.parse(text)
            assertEquals(db.boundaries(),restored.boundaries);assertEquals(observations,restored.boundaryObservations)
            assertEquals(2,db.diagnostics().size)
            assertTrue(db.diagnostics().first().contains("retiredSegmentOverrides"))
        }
        RoadDb(context,name).use { db ->
            val replay=DrivingLimitPipeline()
            val before=replay.evaluate(fix(270.0,1000),listOf(old,next),db.overrides(),emptyMap(),db.boundaries(),db.observations(),1000,20_000)
            assertEquals(60,before.applyTo(DriveState()).limitMph);assertEquals(40,before.upcoming!!.mph)
            val after=replay.evaluate(fix(320.0,2000),listOf(old,next),db.overrides(),emptyMap(),db.boundaries(),db.observations(),2000,21_000)
            assertEquals(40,after.decision.mph)
            val reverse=DrivingLimitPipeline().evaluate(fix(270.0,1000,180.0),listOf(old,next),db.overrides(),emptyMap(),db.boundaries(),db.observations(),1000,22_000)
            // The learned boundary is an ordinary, evidenced two-way UK boundary.
            // In reverse, the vehicle is still on the 40 side and the same
            // physical boundary must be active for the opposite approach.
            assertEquals(40,reverse.decision.mph);assertTrue(reverse.decision.boundaryApplied)
            assertEquals(60,reverse.upcoming?.mph)
        }
    }
}
