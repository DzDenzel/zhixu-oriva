package com.planlock.app.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SyncCoordinatorTest {
    @Test fun coldSyncHappensOnceButPageEntriesNeverRequest() = runBlocking {
        var calls = 0
        val coordinator = SyncCoordinator(this) { calls++ }
        coordinator.coldStart()?.join()
        repeat(6) { coordinator.pageEntered() }
        coordinator.coldStart()?.join()
        assertEquals(1, calls)
        assertEquals(1L, coordinator.state.value.revision)
    }
    @Test fun simultaneousRefreshesJoinSameWorkAndFailureCanRetry() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) {
            calls++
            gate.await()
            if (calls == 1) error("offline")
        }
        val one = coordinator.refresh()
        val two = coordinator.refresh()
        assertSame(one, two)
        gate.complete(Unit)
        one.join()
        assertNotNull(coordinator.state.value.error)
        coordinator.refresh().join()
        assertEquals(2, calls)
        assertNull(coordinator.state.value.error)
        assertFalse(coordinator.state.value.running)
    }
    @Test fun everyForegroundChecksOnceWithoutAnElapsedTimeCondition() = runBlocking {
        var calls = 0
        val coordinator = SyncCoordinator(this) { calls++ }
        coordinator.coldStart()?.join()
        assertEquals(1, calls)
        // Page use without a foreground transition must never add a request.
        repeat(6) { coordinator.pageEntered() }
        yield()
        assertEquals(1, calls)
        // No elapsed time passes; both distinct returns to foreground still check.
        coordinator.foregrounded().join()
        assertEquals(2, calls)
        coordinator.foregrounded().join()
        assertEquals(3, calls)
    }

    @Test fun foregroundJoinsAColdCheckAlreadyInFlight() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) { calls++; gate.await() }
        val cold = coordinator.coldStart()
        val resumed = coordinator.foregrounded()
        assertSame(cold, resumed)
        gate.complete(Unit)
        cold?.join()
        assertEquals(1, calls)
    }
}
