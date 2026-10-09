package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test

class DrivingFixQueueTest {
    private fun fix(t: Long)=Fix(GeoPoint(53.5,-2.8),5.0,10.0,1.0,0.0,t)
    @Test fun latestPendingFixIsDrainedAndOldResultsAreRejected() {
        val q=DrivingFixQueue()
        q.offer(fix(1000),20.0);val old=q.take()!!
        q.offer(fix(2000),25.0);q.offer(fix(3000),28.0)
        assertFalse(q.current(old.fix,3000));assertFalse(q.accepts(fix(2000),3000))
        assertFalse(q.accepts(fix(3000),3000));assertFalse(q.accepts(fix(9000),3000))
        assertEquals(3000L,q.take()!!.fix.elapsedMs);assertNull(q.take())
        assertFalse(q.current(fix(3000),8001))
    }
    @Test fun liveResponseCanRetryOnlyItsOwnStillCurrentFrame() {
        val q=DrivingFixQueue();q.offer(fix(1000),20.0);val frame=q.take()!!
        q.retry(frame);assertEquals(frame,q.take())
        q.offer(fix(2000),25.0);q.retry(frame)
        assertEquals(2000L,q.take()!!.fix.elapsedMs)
    }
}
