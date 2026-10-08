package uk.co.traynor.speedbuddy

import io.requery.android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Exercises the actual staging, validation and pointer switch used by Offline Road Data. */
@RunWith(AndroidJUnit4::class)
class RegionalPackStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun clean() { File(context.filesDir,"regional-road-packs").deleteRecursively() }
    private fun digest(file: File)=MessageDigest.getInstance("SHA-256").let { d -> FileInputStream(file).use { input ->
        val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n)};d.digest().joinToString(""){"%02x".format(it)} } }
    private fun pack(id: String, version: String)=customPack(id,version) {
        execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
        execSQL("INSERT INTO metadata VALUES('formatVersion','1')")
        execSQL("INSERT INTO metadata VALUES('matcherVersion','1')")
        execSQL("INSERT INTO metadata VALUES('coverage','[[[[-3.0,53.0],[-2.0,53.0],[-2.0,54.0],[-3.0,54.0],[-3.0,53.0]]]]')")
        execSQL("INSERT INTO metadata VALUES('dataset','{\"region\":\"$id\"}')")
        execSQL("CREATE TABLE roads(osm_way_id INTEGER PRIMARY KEY,coordinates TEXT NOT NULL,tags TEXT NOT NULL)")
        execSQL("INSERT INTO roads VALUES(1,'[[-2.8,53.5],[-2.8,53.501]]','{\"highway\":\"residential\",\"maxspeed\":\"30 mph\"}')")
        execSQL("CREATE VIRTUAL TABLE roads_rtree USING rtree(osm_way_id,min_lon,max_lon,min_lat,max_lat)")
        execSQL("INSERT INTO roads_rtree VALUES(1,-2.8,-2.8,53.5,53.501)")
    }
    private fun customPack(id: String, version: String, populate: SQLiteDatabase.() -> Unit): Pair<RegionalPackDescriptor,File> {
        val raw=File(context.cacheDir,"$id-$version.sqlite");raw.delete()
        SQLiteDatabase.openOrCreateDatabase(raw,null).use(populate)
        val gzip=File(context.cacheDir,"$id-$version.gz");GZIPOutputStream(FileOutputStream(gzip)).use { out -> raw.inputStream().use { it.copyTo(out) } }
        val d=RegionalPackDescriptor(id,id.replaceFirstChar { it.uppercase() },version,1,"2026-10-07T00:00:00Z",gzip.length(),raw.length(),digest(gzip),digest(raw),
            "https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$id/$version/roads.sqlite.gz","https://api.jtwebsolutions.co.uk/speedbuddy/v1/packs/$id/$version/manifest.json")
        raw.delete();return d to gzip
    }
    @Test fun successfulActivationAndDeleteKeepTheOtherRegionalPack() {
        clean();val store=RegionalPackStore(context)
        val (lancashire,lancashireGzip)=pack("lancashire","1");val (merseyside,merseysideGzip)=pack("merseyside","1")
        store.install(lancashire,lancashireGzip);store.install(merseyside,merseysideGzip)
        assertEquals(setOf("lancashire","merseyside"),store.installed().map { it.descriptor.id }.toSet())
        store.delete("lancashire")
        assertNull(store.active("lancashire"));assertNotNull(store.active("merseyside"))
        clean()
    }
    @Test fun failedUpdatePreservesThePreviouslyActiveVerifiedPack() {
        clean();val store=RegionalPackStore(context);val (old,oldGzip)=pack("lancashire","1")
        store.install(old,oldGzip);val before=store.active("lancashire")!!.readBytes()
        val (replacement,replacementGzip)=pack("lancashire","2");replacementGzip.appendBytes(byteArrayOf(1))
        assertTrue(runCatching { store.install(replacement,replacementGzip) }.isFailure)
        assertArrayEquals(before,store.active("lancashire")!!.readBytes());assertEquals("1",store.installed().single().descriptor.version)
        clean()
    }
    @Test fun checksumCorruptGzipAndBoundedDecompressionRejectBeforeActivationThenRetryWorks() {
        clean();val store=RegionalPackStore(context);val (valid,gzip)=pack("lancashire","3")
        assertTrue(runCatching { store.install(valid.copy(sha256="0".repeat(64)),gzip) }.isFailure)
        val corrupt=File(context.cacheDir,"corrupt-regional.gz").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val corruptDescriptor=valid.copy(downloadBytes=corrupt.length(),sha256=digest(corrupt))
        assertTrue(runCatching { store.install(corruptDescriptor,corrupt) }.isFailure)
        assertTrue(runCatching { store.install(valid.copy(uncompressedBytes=1),gzip) }.isFailure)
        assertNull(store.active("lancashire"))
        store.install(valid,gzip)
        assertEquals("3",store.installed().single().descriptor.version)
        clean()
    }
    @Test fun missingMetadataUnsupportedSchemaAndMissingRtreeNeverBecomeActive() {
        clean();val store=RegionalPackStore(context)
        val (missingMetadata,missingMetadataGzip)=customPack("lancashire","metadata") { execSQL("CREATE TABLE roads_rtree(osm_way_id INTEGER)") }
        assertTrue(runCatching { store.install(missingMetadata,missingMetadataGzip) }.isFailure)
        val (unsupported,unsupportedGzip)=customPack("lancashire","schema") {
            execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            execSQL("INSERT INTO metadata VALUES('formatVersion','2')")
            execSQL("INSERT INTO metadata VALUES('matcherVersion','1')")
            execSQL("INSERT INTO metadata VALUES('coverage','{}')")
            execSQL("INSERT INTO metadata VALUES('dataset','{\"region\":\"lancashire\"}')")
            execSQL("CREATE TABLE roads_rtree(osm_way_id INTEGER)")
        }
        assertTrue(runCatching { store.install(unsupported,unsupportedGzip) }.isFailure)
        val (missingRtree,missingRtreeGzip)=customPack("lancashire","rtree") {
            execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            execSQL("INSERT INTO metadata VALUES('formatVersion','1')")
            execSQL("INSERT INTO metadata VALUES('matcherVersion','1')")
            execSQL("INSERT INTO metadata VALUES('coverage','{}')")
            execSQL("INSERT INTO metadata VALUES('dataset','{\"region\":\"lancashire\"}')")
        }
        assertTrue(runCatching { store.install(missingRtree,missingRtreeGzip) }.isFailure)
        assertNull(store.active("lancashire"));clean()
    }
    @Test fun verifiedUpdateAtomicallySwitchesOnlyItsOwnRegion() {
        clean();val store=RegionalPackStore(context)
        val (old,oldGzip)=pack("lancashire","old");val (newer,newGzip)=pack("lancashire","new")
        val (merseyside,merseysideGzip)=pack("merseyside","1")
        store.install(old,oldGzip);store.install(merseyside,merseysideGzip);store.install(newer,newGzip)
        assertEquals("new",store.installed().single { it.descriptor.id=="lancashire" }.descriptor.version)
        assertEquals("1",store.installed().single { it.descriptor.id=="merseyside" }.descriptor.version)
        clean()
    }
    @Test fun productionFormatRtreePackValidatesAndReturnsSpatialRoadCandidates() {
        clean();val store=RegionalPackStore(context);val (pack,gzip)=pack("lancashire","rtree-live")
        store.install(pack,gzip)
        val result=RegionalPackMatcher(context).match(Fix(GeoPoint(53.5005,-2.8),5.0,10.0,1.0,0.0,1_000))
        assertEquals(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,result.state)
        assertEquals("osm:1",result.match?.road?.id)
        clean()
    }
    @Test fun accurateStationaryFixStillMatchesAnUnambiguousRegionalRoad() {
        clean();val store=RegionalPackStore(context);val (pack,gzip)=pack("lancashire","stationary")
        store.install(pack,gzip)
        // This is the owner-device case: a fresh 4 m fix at rest has no movement-derived heading.
        val result=RegionalPackMatcher(context).match(Fix(GeoPoint(53.5005,-2.8),4.0,0.0,0.5,null,1_000))
        assertEquals(RoadProviderState.ROAD_MATCHED_LIMIT_KNOWN,result.state)
        assertEquals("osm:1",result.match?.road?.id)
        clean()
    }
}
