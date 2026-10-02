package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RoadDownloadTest {
    @Test fun cancellationDuringFactoryNeverStartsHttp() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("road-download-budget",0).edit().clear().commit()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val writes=AtomicInteger()
        val factory={
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            object: HttpURLConnection(URL("https://example.invalid")) {
                override fun disconnect()=Unit
                override fun usingProxy()=false
                override fun connect()=Unit
                override fun getOutputStream(): ByteArrayOutputStream { writes.incrementAndGet();return ByteArrayOutputStream() }
            }
        }
        val job=launch(Dispatchers.IO) { RoadDownload(context,factory).fetch(RoadTile.at(GeoPoint(53.0,-2.0))) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
        job.cancel();release.countDown();job.join()
        assertEquals(0,writes.get())
        context.getSharedPreferences("road-download-budget",0).edit().clear().commit()
    }
    /** Exercise actual download cancellation without depending on a public endpoint or live internet. */
    @Test fun stopClosesInFlightRequestAndRestartCannotOverlap() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("road-download-budget",0).edit().clear().commit()
        val entered=CountDownLatch(1);val closed=CountDownLatch(1);val opens=AtomicInteger()
        val factory={
            opens.incrementAndGet()
            object: HttpURLConnection(URL("https://example.invalid")) {
                override fun disconnect() { closed.countDown() }
                override fun usingProxy()=false
                override fun connect()=Unit
                override fun getResponseCode()=200
                override fun getOutputStream()=ByteArrayOutputStream()
                override fun getInputStream(): InputStream = object: InputStream() {
                    override fun read(): Int { entered.countDown();check(closed.await(5,TimeUnit.SECONDS));throw java.io.IOException("cancelled") }
                }
            }
        }
        val first=launch(Dispatchers.IO) { RoadDownload(context,factory).fetch(RoadTile.at(GeoPoint(53.0,-2.0))) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
        val restarted=launch(Dispatchers.IO) { RoadDownload(context,factory).fetch(RoadTile.at(GeoPoint(53.0,-2.0))) }
        delay(100)
        assertEquals(1,opens.get())
        first.cancelAndJoin()
        assertEquals(0L,closed.count)
        // Restart is still waiting for the shared persisted 30-second request slot.
        delay(100);assertEquals(1,opens.get());restarted.cancelAndJoin()
        context.getSharedPreferences("road-download-budget",0).edit().clear().commit()
    }
}
