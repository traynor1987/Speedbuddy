package uk.co.traynor.speedbuddy

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Delays real private SQLite inventory reads while exercising the production screen. */
class RegionalInventoryUiTest {
    @get:Rule val compose=createComposeRule()
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val context by lazy { object: ContextWrapper(base) {
        override fun getFilesDir()=File(base.cacheDir,"inventory-ui-files").apply { mkdirs() }
    } }
    private var previousCredential: String?=null
    @Before fun before() {
        File(base.cacheDir,"inventory-ui-files").deleteRecursively()
        previousCredential=RegionalRoadDataAccess(base).credential();RegionalRoadDataAccess(base).save("inventory-test")
    }
    @After fun after() {
        File(base.cacheDir,"inventory-ui-files").deleteRecursively()
        previousCredential?.let { RegionalRoadDataAccess(base).save(it) } ?: RegionalRoadDataAccess(base).clear()
    }
    private fun install(): RegionalPackDescriptor {
        val raw=File(context.filesDir,"inventory-fixture.sqlite")
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            for((k,v) in mapOf("formatVersion" to "1","matcherVersion" to "\"distance-heading-oneway-continuity-v1\"","dataset" to "{\"region\":\"lancashire\"}","coverage" to "[[[[-3,53],[-2,53],[-2,54],[-3,54],[-3,53]]]]")) db.execSQL("INSERT INTO metadata VALUES(?,?)",arrayOf(k,v))
            db.execSQL("CREATE TABLE roads(osm_way_id INTEGER PRIMARY KEY,coordinates TEXT,tags TEXT)")
            db.execSQL("CREATE VIRTUAL TABLE roads_rtree USING rtree(osm_way_id,min_lon,max_lon,min_lat,max_lat)")
        }
        fun hash(f: File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val gzip=File(context.filesDir,"inventory-fixture.gz")
        GZIPOutputStream(gzip.outputStream()).use { out -> raw.inputStream().use { it.copyTo(out) } }
        val d=RegionalPackDescriptor("lancashire","Lancashire","20261006-1",1,"2026-10-06T00:00:00Z",gzip.length(),raw.length(),hash(gzip),hash(raw),
            "https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/20261006-1/roads.sqlite.gz","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/lancashire/20261006-1/manifest.json")
        try { RegionalPackStore(context).install(d,gzip) } finally { raw.delete();gzip.delete() }
        return d
    }
    private fun lifecycle(d: RegionalPackDescriptor,scan: () -> RegionalPackStore.Inventory): RegionalPackLifecycle {
        val region=JSONObject().put("id",d.id).put("displayName",d.displayName).put("version",d.version).put("schemaVersion",d.schemaVersion)
            .put("osmTimestamp",d.osmTimestamp).put("downloadBytes",d.downloadBytes).put("uncompressedBytes",d.uncompressedBytes)
            .put("sha256",d.sha256).put("uncompressedSha256",d.uncompressedSha256).put("downloadUrl",d.downloadUrl).put("manifestUrl",d.manifestUrl)
        val catalogue=JSONObject().put("catalogueVersion",1).put("generatedAt","2026-10-08T00:00:00Z").put("regions",JSONArray().put(region)).toString()
        return RegionalPackLifecycle(context,{"inventory-test"},{ url -> object: HttpURLConnection(url) {
            override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getInputStream()=catalogue.byteInputStream()
        } },inventoryScan=scan)
    }
    private fun awaitText(value: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty() } }
    private fun assertUnresolved(expectPrevious: Boolean=false) {
        compose.onNodeWithText("Checking installed road packs…").assertExists()
        compose.onNodeWithText("No installed regional packs").assertDoesNotExist()
        if(!expectPrevious) compose.onAllNodesWithText("Stored regional files:",substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("Not installed",substring=true).assertCountEquals(0)
    }
    @Test fun delayedStartupNeverOffersDownloadOrReportsInstalledPackAbsent() {
        val d=install();val store=RegionalPackStore(context)
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val lifecycle=lifecycle(d) { entered.countDown();check(finish.await(30,TimeUnit.SECONDS));store.inventory() }
        try {
            compose.setContent { MaterialTheme { OfflineRoadDataScreen(context,lifecycle,{}) } }
            assertTrue(entered.await(10,TimeUnit.SECONDS));awaitText("Lancashire")
            assertUnresolved();compose.onNodeWithText("Download").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Delete").assertDoesNotExist()
            finish.countDown();awaitText("Installed 20261006-1")
            compose.onNodeWithText("Checking installed road packs…").assertDoesNotExist()
            compose.onNodeWithText("Re-download").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Delete").assertIsEnabled()
        } finally { finish.countDown() }
    }
    @Test fun refreshRetainsVerifiedCardAndDisablesConflictingActions() {
        val d=install();val store=RegionalPackStore(context);val calls=AtomicInteger()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val lifecycle=lifecycle(d) { if(calls.incrementAndGet()>1) { entered.countDown();check(finish.await(30,TimeUnit.SECONDS)) };store.inventory() }
        val pool=Executors.newSingleThreadExecutor()
        try {
            compose.setContent { MaterialTheme { OfflineRoadDataScreen(context,lifecycle,{}) } };awaitText("Installed 20261006-1")
            val refresh=pool.submit { runBlocking { lifecycle.refreshInventory() } }
            assertTrue(entered.await(10,TimeUnit.SECONDS));awaitText("Checking installed road packs…")
            assertUnresolved(expectPrevious=true);compose.onNodeWithText("Installed 20261006-1",substring=true).assertExists()
            compose.onNodeWithText("Stored regional files:",substring=true).assertExists()
            compose.onNodeWithText("Re-download").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Delete").assertIsNotEnabled()
            finish.countDown();refresh.get(10,TimeUnit.SECONDS);awaitText("Installed 20261006-1")
        } finally { finish.countDown();pool.shutdownNow() }
    }
    @Test fun recreatedScreenChecksStoredPackAgainWithoutFalseEmptyState() {
        val d=install();val store=RegionalPackStore(context);val generation=mutableIntStateOf(0)
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val original=lifecycle(d,store::inventory)
        val recreated=lifecycle(d) { entered.countDown();check(finish.await(30,TimeUnit.SECONDS));store.inventory() }
        try {
            compose.setContent { key(generation.intValue) { MaterialTheme { OfflineRoadDataScreen(context,if(generation.intValue==0) original else recreated,{}) } } }
            awaitText("Installed 20261006-1");compose.runOnIdle { generation.intValue=1 }
            assertTrue(entered.await(10,TimeUnit.SECONDS));awaitText("Lancashire")
            assertUnresolved();compose.onNodeWithText("Download").performScrollTo().assertIsNotEnabled()
            finish.countDown();awaitText("Installed 20261006-1")
            assertEquals("20261006-1",RegionalPackStore(context).inventory().installed.single().descriptor.version)
        } finally { finish.countDown() }
    }
}
