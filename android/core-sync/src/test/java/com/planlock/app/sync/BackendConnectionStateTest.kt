package com.planlock.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackendConnectionStateTest {
    @Test fun noConfigurationIsLocalEvenWithOldSuccess() {
        assertEquals(BackendConnectionState.LOCAL_ONLY, backendConnectionState(false, SyncState(lastRoundSucceeded = true)))
    }
    @Test fun configuredButNeverConfirmedDoesNotClaimConnected() {
        assertEquals(BackendConnectionState.UNREACHABLE, backendConnectionState(true, SyncState()))
    }
    @Test fun pageClassificationUsesExistingOutcomeWithoutSendingRequests() = runBlocking {
        var requests = 0
        val coordinator = SyncCoordinator(this) { requests++ }
        coordinator.refresh().join()
        repeat(10) { assertEquals(BackendConnectionState.CONNECTED, backendConnectionState(true, coordinator.state.value)) }
        assertEquals(1, requests)
        coordinator.clearError()
        assertEquals(BackendConnectionState.UNREACHABLE, backendConnectionState(true, coordinator.state.value))
        assertEquals(1, requests)
    }
    @Test fun failedRoundAndInflightRetryCannotMasqueradeAsSuccess() = runBlocking {
        var requests = 0
        val retry = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) {
            when (++requests) {
                1 -> Unit
                2 -> error("offline")
                else -> { entered.complete(Unit); retry.await() }
            }
        }
        coordinator.refresh().join()
        assertEquals(BackendConnectionState.CONNECTED, backendConnectionState(true, coordinator.state.value))
        coordinator.refresh().join()
        assertEquals(BackendConnectionState.UNREACHABLE, backendConnectionState(true, coordinator.state.value))
        val running = coordinator.refresh()
        entered.await()
        assertEquals(BackendConnectionState.UNREACHABLE, backendConnectionState(true, coordinator.state.value))
        retry.complete(Unit); running.join()
        assertEquals(BackendConnectionState.CONNECTED, backendConnectionState(true, coordinator.state.value))
    }
    @Test fun obsoleteConnectionRoundCannotPublishSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) { entered.complete(Unit); finish.await() }
        val job = coordinator.refresh()
        entered.await(); coordinator.clearError(); finish.complete(Unit); job.join()
        assertEquals(BackendConnectionState.UNREACHABLE, backendConnectionState(true, coordinator.state.value))
    }
}
