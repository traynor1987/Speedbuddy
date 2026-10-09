package uk.co.traynor.speedbuddy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RegionalInventoryTest {
    @Test fun startupIsCheckingUntilCompleteOfflineVerificationReturns() {
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val expected=RegionalPackStore.Inventory(emptyList(),listOf("lancashire"),storedBytes=205_600_000)
        val inventory=RegionalInventory { entered.countDown();check(finish.await(5,TimeUnit.SECONDS));expected }
        assertTrue(inventory.state.value is RegionalInventoryState.Checking)
        val pool=Executors.newSingleThreadExecutor()
        try {
            val refresh=pool.submit { runBlocking { inventory.refresh() } }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(inventory.state.value is RegionalInventoryState.Checking)
            finish.countDown();refresh.get(5,TimeUnit.SECONDS)
            assertEquals(expected,(inventory.state.value as RegionalInventoryState.Ready).inventory)
        } finally { finish.countDown();pool.shutdownNow() }
    }
    @Test fun reportedStorageFailureIsAnErrorWithCompleteRetainedInventory()=runBlocking {
        val expected=RegionalPackStore.Inventory(emptyList(),listOf("damaged"),error="Regional storage unavailable",storedBytes=42)
        val inventory=RegionalInventory { expected };inventory.refresh()
        val failure=inventory.state.value as RegionalInventoryState.Error
        assertEquals(expected,failure.previous);assertEquals(expected.error,failure.message)
    }
    @Test fun unreadableStartupNeverBecomesAnEmptyReadyInventory()=runBlocking {
        val inventory=RegionalInventory { error("storage unreadable") }
        inventory.refresh()
        val failure=inventory.state.value as RegionalInventoryState.Error
        assertNull(failure.previous)
    }
    @Test fun recreatedControllerRequiresItsOwnVerification()=runBlocking {
        val expected=RegionalPackStore.Inventory(emptyList(),emptyList())
        val first=RegionalInventory { expected };first.refresh()
        assertTrue(first.state.value is RegionalInventoryState.Ready)
        assertTrue(RegionalInventory { expected }.state.value is RegionalInventoryState.Checking)
    }
    @Test fun failedRefreshRetainsLastCompleteInventoryAndCanRetry()=runBlocking {
        val expected=RegionalPackStore.Inventory(emptyList(),listOf("missing"),storedBytes=42)
        var fail=false
        val inventory=RegionalInventory { if(fail) error("unreadable directory") else expected }
        inventory.refresh();fail=true;inventory.refresh()
        val error=inventory.state.value as RegionalInventoryState.Error
        assertEquals(expected,error.previous);assertEquals("Local pack inventory unavailable",error.message)
        fail=false;inventory.refresh();assertEquals(expected,(inventory.state.value as RegionalInventoryState.Ready).inventory)
    }
    @Test fun concurrentRefreshesNeverRunVerificationTogether() {
        val active=AtomicInteger();val maximum=AtomicInteger();val calls=AtomicInteger()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val inventory=RegionalInventory {
            val count=active.incrementAndGet();maximum.updateAndGet { maxOf(it,count) }
            try { if(calls.incrementAndGet()==1) { entered.countDown();check(finish.await(5,TimeUnit.SECONDS)) };RegionalPackStore.Inventory(emptyList(),emptyList()) }
            finally { active.decrementAndGet() }
        }
        val pool=Executors.newFixedThreadPool(2)
        try {
            val first=pool.submit { runBlocking { inventory.refresh() } };assertTrue(entered.await(5,TimeUnit.SECONDS))
            val second=pool.submit { runBlocking { inventory.refresh() } }
            finish.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS)
            assertEquals(2,calls.get());assertEquals(1,maximum.get());assertTrue(inventory.state.value is RegionalInventoryState.Ready)
        } finally { finish.countDown();pool.shutdownNow() }
    }
}
