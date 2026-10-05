package uk.co.traynor.speedbuddy

import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerBackupWorkTest {
    @Test fun recreationCannotReleaseGuardWhileCancelledRestoreIoStillRuns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        assertFalse(OwnerBackupWork.busy.value)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.lifecycleScope.launch {
                        try {
                            OwnerBackupWork.perform {
                                withContext(Dispatchers.IO) {
                                    started.countDown()
                                    check(release.await(20, TimeUnit.SECONDS))
                                }
                            }
                        } finally { finished.countDown() }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                scenario.recreate()
                scenario.onActivity { assertTrue(OwnerBackupWork.busy.value) }
                runBlocking {
                    var entered = false
                    val attempt = runCatching { OwnerBackupWork.perform { entered = true } }
                    assertFalse(entered)
                    assertTrue(attempt.exceptionOrNull() is IllegalStateException)
                }
                assertTrue(OwnerBackupWork.busy.value)
            } finally { release.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertFalse(OwnerBackupWork.busy.value)
        }
    }
}
