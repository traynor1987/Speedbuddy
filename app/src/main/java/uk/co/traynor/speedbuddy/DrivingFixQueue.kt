package uk.co.traynor.speedbuddy

/** Main-thread location ordering with one pending latest frame, never an unbounded GPS backlog. */
internal class DrivingFixQueue {
    data class Frame(val fix: Fix, val speedMph: Double?)
    private var latestElapsedMs: Long? = null
    private var pending: Frame? = null
    fun accepts(fix: Fix, now: Long) = now-fix.elapsedMs in 0..5000 &&
        latestElapsedMs?.let { fix.elapsedMs>it } != false
    fun offer(fix: Fix, speed: Double?) { latestElapsedMs=fix.elapsedMs;pending=Frame(fix,speed) }
    fun retry(frame: Frame) { if(frame.fix.elapsedMs==latestElapsedMs && pending==null) pending=frame }
    fun takeWhenReady(ready: Boolean): Frame? = if(ready) take() else null
    fun take(): Frame? = pending.also { pending=null }
    fun current(fix: Fix, now: Long) = fix.elapsedMs==latestElapsedMs && now-fix.elapsedMs in 0..5000
}
