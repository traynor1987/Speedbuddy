package uk.co.traynor.speedbuddy

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class RegionalManagementUiTest {
    @get:Rule val compose=createComposeRule()
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val context by lazy { object: ContextWrapper(base) {
        override fun getFilesDir()=File(base.cacheDir,"cycle3-ui-files").apply { mkdirs() }
    } }
    private var originalCredential: String?=null
    @Before fun before() { File(base.cacheDir,"cycle3-ui-files").deleteRecursively();originalCredential=RegionalRoadDataAccess(base).credential();RegionalRoadDataAccess(base).clear() }
    @After fun after() { File(base.cacheDir,"cycle3-ui-files").deleteRecursively();originalCredential?.let { RegionalRoadDataAccess(base).save(it) } ?: RegionalRoadDataAccess(base).clear() }
    private fun install(id: String): RegionalPackDescriptor {
        val version="ui-${java.util.UUID.randomUUID()}";val raw=File(base.cacheDir,"$version.sqlite")
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            for((k,v) in mapOf("formatVersion" to "1","matcherVersion" to "1","dataset" to "{\"region\":\"$id\"}","coverage" to "[[[[-3,53],[-2,53],[-2,54],[-3,54],[-3,53]]]]")) db.execSQL("INSERT INTO metadata VALUES(?,?)",arrayOf(k,v))
            db.execSQL("CREATE TABLE roads(osm_way_id INTEGER PRIMARY KEY,coordinates TEXT,tags TEXT)")
            db.execSQL("CREATE VIRTUAL TABLE roads_rtree USING rtree(osm_way_id,min_lon,max_lon,min_lat,max_lat)")
        }
        fun hash(f: File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val gzip=File(base.cacheDir,"$version.gz");GZIPOutputStream(gzip.outputStream()).use { out -> raw.inputStream().use { it.copyTo(out) } }
        val d=RegionalPackDescriptor(id,id.replaceFirstChar { it.uppercase() },version,1,"2026-10-08T00:00:00Z",gzip.length(),raw.length(),hash(gzip),hash(raw),
            "https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$id/$version/roads.sqlite.gz","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$id/$version/manifest.json")
        try { RegionalPackStore(context).install(d,gzip) } finally { raw.delete();gzip.delete() }
        return d
    }
    private fun awaitPacks() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Lancashire").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("Merseyside").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun installedPacksRemainVisibleWithoutAccessAndAfterScreenRestoration() {
        install("lancashire");install("merseyside")
        val lifecycle=RegionalPackLifecycle(context,{null})
        val restore=StateRestorationTester(compose)
        restore.setContent { MaterialTheme { OfflineRoadDataScreen(context,lifecycle,{}) } }
        awaitPacks();compose.onNodeWithText("Lancashire").performScrollTo().assertIsDisplayed();compose.onNodeWithText("Merseyside").performScrollTo().assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore();awaitPacks()
        assertEquals(2,RegionalPackStore(context).installed().size)
    }
    @Test fun authenticationFailureAndRemovingAccessKeepInstalledCardsAndDeleteWorks() {
        install("lancashire");install("merseyside");RegionalRoadDataAccess(base).save("test-token")
        val lifecycle=RegionalPackLifecycle(context,{"expired"},{ url -> object: java.net.HttpURLConnection(url) {
            override fun connect() {};override fun disconnect() {};override fun usingProxy()=false;override fun getResponseCode()=401
        } })
        compose.setContent { MaterialTheme { OfflineRoadDataScreen(context,lifecycle,{}) } };awaitPacks()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Regional catalogue unavailable (HTTP 401)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove access").performScrollTo().performClick();awaitPacks()
        compose.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { RegionalPackStore(context).installed().size==1 }
        assertEquals("merseyside",RegionalPackStore(context).installed().single().descriptor.id)
        assertFalse(File(context.filesDir,"regional-road-packs/lancashire").exists())
    }
    @Test fun unavailableDatabaseStillHasAnOfflineManagementCard() {
        val d=install("lancashire");install("merseyside");RegionalPackStore(context).active(d.id)!!.delete()
        compose.setContent { MaterialTheme { OfflineRoadDataScreen(context,RegionalPackLifecycle(context,{null}),{}) } };awaitPacks()
        compose.onNodeWithText("Database missing; re-download or delete").performScrollTo().assertIsDisplayed()
    }
    @Test fun diagnosticsShowsRegionalProvenanceAndKeepsClearingDisabledWhileDriving() {
        val state=DriveState(active=true,roadData=RoadDataDiagnostics("Regional offline","ROAD_MATCHED_LIMIT_UNKNOWN","Covered",listOf(RegionalPackInfo("Lancashire","lancashire","v1","2026-10-07T00:00:00Z")),sampleElapsedMs=android.os.SystemClock.elapsedRealtime()))
        compose.setContent { MaterialTheme { DiagnosticsScreen(state,{_,_->},{}) } }
        compose.onNodeWithText("ROAD_MATCHED_LIMIT_UNKNOWN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lancashire · v1 · 2026-10-07T00:00:00Z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear local diagnostics").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Legacy coverage tiles").assertExists()
    }
    @Test fun failedRedownloadKeepsOldPackAndReenablesManagementControls() {
        install("lancashire");install("merseyside");RegionalRoadDataAccess(base).save("test-token")
        val lifecycle=RegionalPackLifecycle(context,{"expired"},{ url -> object: java.net.HttpURLConnection(url) {
            override fun connect() {};override fun disconnect() {};override fun usingProxy()=false;override fun getResponseCode()=401
        } })
        compose.setContent { MaterialTheme { OfflineRoadDataScreen(context,lifecycle,{}) } };awaitPacks()
        compose.onAllNodesWithText("Re-download")[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Lancashire: Regional pack download failed (HTTP 401)").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Delete")[0].assertIsEnabled()
        compose.onAllNodesWithText("Re-download")[0].assertIsEnabled()
        assertEquals(2,RegionalPackStore(context).installed().size)
    }

}
