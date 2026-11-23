package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class NotificationSilenceSessionTest {

    private class FakeListenerHandle(
        var dispatchResult: Boolean = true,
        var hints: Int? = 0,
    ) : SilenceListenerHandle {
        val state = ListenerHintState()
        val applied = mutableListOf<Boolean>()

        override fun applyRequired(required: Boolean) {
            state.applyIfNeeded(required) { target ->
                applied += target
                dispatchResult
            }
        }

        override fun resetRequested() {
            state.reset()
        }

        override fun currentHostHints(): Int? = hints
    }

    @Test fun listenerHintStateDeduplicatesSuccessesRetainsFailureAndResetsCleanly() {
        val state = ListenerHintState()
        val dispatches = mutableListOf<Boolean>()

        // Failed dispatch must not cache requested=true.
        assertFalse(state.applyIfNeeded(true) { dispatches += it; false })
        assertNull(state.currentRequested())

        // Retry succeeds and deduplicates identical target.
        assertTrue(state.applyIfNeeded(true) { dispatches += it; true })
        assertEquals(true, state.currentRequested())
        assertFalse(state.applyIfNeeded(true) { dispatches += it; true })

        // Reset clears requested so the next apply dispatches even for the same boolean.
        state.reset()
        assertNull(state.currentRequested())
        assertTrue(state.applyIfNeeded(true) { dispatches += it; true })
        assertEquals(listOf(true, true, true), dispatches)
    }

    @Test fun initializeIsIdempotentAndRequiredBeforeInitRecoversOnceInitialized() {
        var granted = true
        var now = 1_000L
        var rebinds = 0
        val session = NotificationSilenceSession(
            isGranted = { granted },
            nowElapsedMs = { now },
            requestRebind = { rebinds++; true },
        )

        // Before initialize(), setRequired records intent without crashing or rebinding.
        session.setRequired(true)
        assertTrue(session.isRequired())
        assertEquals(0, rebinds)

        // Idempotent initialization enables refreshConnection while preserving required state.
        session.initialize()
        session.initialize()
        assertTrue(session.isInitialized())
        assertTrue(session.refreshConnection())
        assertEquals(1, rebinds)
        assertTrue(session.isRequired())
    }

    @Test fun disconnectAndRequiredChangeSyncsImmediatelyOnReconnectWithoutStaleRequested() {
        var now = 0L
        var rebinds = 0
        val session = NotificationSilenceSession(
            isGranted = { true },
            nowElapsedMs = { now },
            requestRebind = { rebinds++; true },
        ).apply { initialize() }

        val service = FakeListenerHandle()
        session.setRequired(true)
        assertEquals(1, rebinds)

        // Connected: applies true.
        session.attach(service)
        assertEquals(listOf(true), service.applied)
        assertEquals(true, service.state.currentRequested())

        // Disconnected: requested must be cleared; setRequired(false) happens offline.
        assertTrue(session.detach(service))
        assertNull(service.state.currentRequested())
        session.setRequired(false)

        // Same reused service instance reconnects: must apply false immediately (not skipped).
        session.attach(service)
        assertEquals(listOf(true, false), service.applied)
        assertEquals(false, service.state.currentRequested())
    }

    @Test fun destroyReleasesAttachedListenerClearsRequestedAndTriggersRebindOnlyIfStillRequired() {
        var now = 100L
        var rebinds = 0
        val session = NotificationSilenceSession(
            isGranted = { true },
            nowElapsedMs = { now },
            requestRebind = { rebinds++; true },
        ).apply { initialize() }

        val service = FakeListenerHandle()
        session.setRequired(true)
        session.attach(service)
        assertEquals(1, rebinds)

        // Direct onDestroy while still attached and required=true:
        // 1) sends false to release hints; 2) clears requested; 3) requests rebind after gate cooldown.
        now = 10_200L
        session.destroy(service)
        assertEquals(listOf(true, false), service.applied)
        assertNull(service.state.currentRequested())
        assertFalse(session.isConnected())
        assertEquals(2, rebinds)

        // Reconnect with a fresh handle immediately restores required=true.
        val replacement = FakeListenerHandle()
        session.attach(replacement)
        assertEquals(listOf(true), replacement.applied)
        assertEquals(true, replacement.state.currentRequested())
    }

    @Test fun staleDetachOrDestroyDoesNotClobberActiveListener() {
        val session = NotificationSilenceSession(
            isGranted = { true },
            nowElapsedMs = { 0L },
            requestRebind = { true },
        ).apply { initialize() }

        val oldService = FakeListenerHandle()
        val newService = FakeListenerHandle()
        session.setRequired(true)
        session.attach(oldService)
        assertTrue(session.detach(oldService))

        session.attach(newService)
        assertTrue(session.isConnected())

        // Late onDestroy from oldService must not detach or release newService.
        session.destroy(oldService)
        assertTrue(session.isConnected())
        assertEquals(listOf(true), oldService.applied)
        assertEquals(listOf(true), newService.applied)
        assertNull(oldService.state.currentRequested())
        assertEquals(true, newService.state.currentRequested())
    }

    @Test fun recoverIfRequiredRespectsGrantAndRebindGateCooldown() {
        var granted = false
        var now = 1_000L
        var rebinds = 0
        val session = NotificationSilenceSession(
            isGranted = { granted },
            nowElapsedMs = { now },
            requestRebind = { rebinds++; true },
        ).apply { initialize() }

        session.setRequired(true)
        session.recoverIfRequired()
        assertEquals(0, rebinds)

        granted = true
        session.recoverIfRequired()
        assertEquals(1, rebinds)

        // Cooldown suppresses duplicate disconnect recovery within 10s.
        now = 10_999L
        session.recoverIfRequired()
        assertEquals(1, rebinds)

        // Once cooldown elapses, recovery succeeds.
        now = 11_000L
        session.recoverIfRequired()
        assertEquals(2, rebinds)

        // When not required, recoverIfRequired never rebinds.
        session.setRequired(false)
        now = 30_000L
        session.recoverIfRequired()
        assertEquals(2, rebinds)
    }
}
