package uk.co.traynor.speedbuddy

import org.junit.Test

/** A full owner APK must package both previously separate feature sets. */
class FullFeatureBuildTest {
    @Test fun fullBuildIncludesMapsUpdatesAndOfflineLimitEngine() {
        for (name in listOf("MapProvider", "UpdateClient", "LimitDecisionEngine")) {
            Class.forName("uk.co.traynor.speedbuddy.$name")
        }
    }
}
