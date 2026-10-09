package uk.co.traynor.speedbuddy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DrivingRecoveryTest {
    @Test fun ownerEditDuringCameraReadRejectsOldSnapshotAndNextReadSeesEdit() = runBlocking {
        var revision=1L
        val stale=readCurrentOwnerSnapshot({revision}) { revision=2; listOf("old camera") }
        assertNull(stale)
        val fresh=readCurrentOwnerSnapshot({revision}) { listOf("corrected camera") }
        assertEquals(2L,fresh!!.first)
        assertEquals(listOf("corrected camera"),fresh.second)
    }
    @Test fun queuedInitializationFixIsAvailableWithoutAnotherGpsCallback() {
        val queue=DrivingFixQueue()
        val fix=Fix(GeoPoint(53.4,-2.8),5.0,0.0,null,null,1000)
        queue.offer(fix,0.0)
        assertNull(queue.takeWhenReady(false))
        assertEquals(fix,queue.takeWhenReady(true)!!.fix)
        assertNull(queue.takeWhenReady(true))
    }
}
