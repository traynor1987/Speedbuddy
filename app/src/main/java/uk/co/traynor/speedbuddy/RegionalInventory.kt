package uk.co.traynor.speedbuddy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Absence is established only after a complete local scan, never while verification is pending. */
internal sealed interface RegionalInventoryState {
    data class Checking(val previous: RegionalPackStore.Inventory? = null): RegionalInventoryState
    data class Ready(val inventory: RegionalPackStore.Inventory): RegionalInventoryState
    data class Error(val previous: RegionalPackStore.Inventory?, val message: String): RegionalInventoryState
}

/** Serializes scans and publishes whole inventories; failures retain the last known complete result. */
internal class RegionalInventory(private val scan: () -> RegionalPackStore.Inventory) {
    private val refreshLock=Mutex()
    private val mutableState=MutableStateFlow<RegionalInventoryState>(RegionalInventoryState.Checking())
    val state: StateFlow<RegionalInventoryState> = mutableState.asStateFlow()
    suspend fun refresh()=refreshLock.withLock {
        val previous=when(val current=mutableState.value) {
            is RegionalInventoryState.Checking -> current.previous
            is RegionalInventoryState.Ready -> current.inventory
            is RegionalInventoryState.Error -> current.previous
        }
        mutableState.value=RegionalInventoryState.Checking(previous)
        try {
            val inventory=scan()
            mutableState.value=inventory.error?.let { RegionalInventoryState.Error(inventory,it) }
                ?: RegionalInventoryState.Ready(inventory)
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(error: Exception) {
            mutableState.value=RegionalInventoryState.Error(previous,"Local pack inventory unavailable")
        }
    }
}
