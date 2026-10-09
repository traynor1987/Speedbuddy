package uk.co.traynor.speedbuddy

/** A response describes its request position, never a newer GPS frame by itself. */
internal data class LiveRoadSample(val fix: Fix,val state: LiveRoadState) {
    fun forFix(current: Fix): LiveRoadState? = state.takeIf {
        current.elapsedMs-fix.elapsedMs in 0..5_000 && current.accuracyM<=20 &&
            Geo.distance(current.point,fix.point)<=100 && current.bearing?.let { heading ->
                fix.bearing?.let { Geo.difference(heading,it)<=25 }
            }==true
    }
}
