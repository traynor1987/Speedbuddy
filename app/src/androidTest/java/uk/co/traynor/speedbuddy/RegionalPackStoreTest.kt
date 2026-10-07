package uk.co.traynor.speedbuddy

import android.database.sqlite.SQLiteDatabase
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
    private fun pack(id: String, version: String): Pair<RegionalPackDescriptor,File> {
        val raw=File(context.cacheDir,"$id-$version.sqlite");raw.delete()
        SQLiteDatabase.openOrCreateDatabase(raw,null).use { db ->
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
            db.execSQL("INSERT INTO metadata VALUES('formatVersion','1')")
            db.execSQL("INSERT INTO metadata VALUES('matcherVersion','1')")
            db.execSQL("INSERT INTO metadata VALUES('coverage','{}')")
            db.execSQL("INSERT INTO metadata VALUES('dataset','{\"region\":\"$id\"}')")
            db.execSQL("CREATE TABLE roads_rtree(osm_way_id INTEGER)")
            db.execSQL("INSERT INTO roads_rtree VALUES(1)")
        }
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
}
