package com.planlock.app.sync

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SyncCoordinatorTest {
    @Test fun backgroundWorkDoesNotSpinAndManualJoinMayShowProgress() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) { gate.await() }
        val cold = coordinator.coldStart()
        yield()
        assertFalse(coordinator.state.value.running)
        assertSame(cold, coordinator.refresh())
        assertTrue(coordinator.state.value.running)
        gate.complete(Unit)
        cold?.join()
        assertFalse(coordinator.state.value.running)
    }
    @Test fun wholeRoundTimeoutIncludesWaitingAndCanRetry() = runBlocking {
        var calls = 0
        val coordinator = SyncCoordinator(this, timeoutMillis = 50) {
            if (++calls == 1) awaitCancellation()
        }
        coordinator.refresh().join()
        assertFalse(coordinator.state.value.running)
        assertNotNull(coordinator.state.value.error)
        coordinator.refresh().join()
        assertEquals(2, calls)
        assertNull(coordinator.state.value.error)
    }
    @Test fun cancellationAlwaysReleasesBusyStateAndAllowsAnotherRound() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(this) {
            if (++calls == 1) { entered.complete(Unit); awaitCancellation() }
        }
        val job = coordinator.refresh()
        entered.await(); job.cancelAndJoin()
        assertFalse(coordinator.state.value.running)
        coordinator.refresh().join()
        assertEquals(2, calls)
        assertNull(coordinator.state.value.error)
    }
    @Test fun clearedConnectionFeedbackCannotBeRestoredByOldInFlightWork() = runBlocking {
        for (requiresLogin in listOf(false, true)) {
            val releasedSession = CompletableDeferred<Unit>()
            val finishOldWork = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator = SyncCoordinator(this) {
                calls++
                releasedSession.complete(Unit)
                finishOldWork.await()
                if (requiresLogin) throw OwnerLoginRequiredException() else error("old connection failed")
            }
            val old = coordinator.refresh()
            releasedSession.await()
            coordinator.clearError()
            finishOldWork.complete(Unit)
            old.join()
            assertNull(coordinator.state.value.error)
            assertFalse(coordinator.state.value.loginRequired)
            assertEquals(1, calls)
        }
    }
    @Test fun localOnlyCanDismissPreviousFailureWithoutStartingAnotherTransfer() = runBlocking {
        var calls = 0
        val coordinator = SyncCoordinator(this) { calls++; error("offline") }
        coordinator.refresh().join()
        assertNotNull(coordinator.state.value.error)
        val before = coordinator.state.value
        coordinator.clearError()
        coordinator.pageEntered()
        assertEquals(1, calls)
        assertEquals(before.copy(error = null), coordinator.state.value)
    }
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
