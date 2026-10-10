package com.planlock.plugin.forceblocker

import com.planlock.plugin.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LimitedRelaxationTest {
    @Test fun offlineLocalPlanWithNegativeIdCanUseTheSameResponsePolicy() {
        val state = LimitedRelaxation(file()) { 1_000_000L }
        state.grant(-42, "example.a", "A", 3_000_000L)
        assertTrue(LimitedRelaxation(file()) { 1_000_000L }.isRelaxed(-42, "example.a"))
    }
    @Test fun rollbackInsideGrantIntervalNeverExtendsTheElapsedDeadline() {
        var wall = 1_000_000L
        var elapsed = 100L
        val state = LimitedRelaxation(file(), monotonicClock = { elapsed }, clock = { wall })
        state.grant(7, "example.a", "A", 3_000_000L)
        wall = 1_300_000L
        elapsed = 600_100L
        assertFalse(state.isRelaxed(7, "example.a"))
        wall = 1_600_000L
        elapsed = 900_100L
        assertFalse(state.isRelaxed(7, "example.a"))
    }
    @Test fun anotherLoadedInstanceSeesASuccessfulGrantWrittenAfterItWasConstructed() {
        val previous = LimitedRelaxation(file()) { 1_000_000L }
        val next = LimitedRelaxation(file()) { 1_000_000L }
        previous.grant(7, "example.a", "A", 3_000_000L)
        assertTrue(next.isRelaxed(7, "example.a"))
    }
    @get:Rule val folder = TemporaryFolder()
    private fun file() = File(folder.root, "state.properties")

    @Test fun selectedAppOnlyAndExactFifteenMinuteBoundarySurviveReload() {
        var now = 1_000_000L
        LimitedRelaxation(file()) { now }.grant(7, "example.video", "视频", 4_000_000L)
        val reloaded = LimitedRelaxation(file()) { now }
        assertTrue(reloaded.isRelaxed(7, "example.video"))
        assertFalse(reloaded.isRelaxed(8, "example.video"))
        assertFalse(reloaded.isRelaxed(7, "example.other"))
        now = 1_899_999L
        assertTrue(reloaded.isRelaxed(7, "example.video"))
        now = 1_900_000L
        assertFalse(reloaded.isRelaxed(7, "example.video"))
    }

    @Test fun secondSelectionReplacesInsteadOfStackingAndEndsWithOccurrence() {
        var now = 1_000_000L
        val state = LimitedRelaxation(file()) { now }
        state.grant(7, "example.a", "A", 1_100_000L)
        state.grant(7, "example.b", "B", 1_100_000L)
        assertFalse(state.isRelaxed(7, "example.a"))
        assertTrue(state.isRelaxed(7, "example.b"))
        now = 1_100_000L
        assertFalse(state.isRelaxed(7, "example.b"))
    }

    @Test fun invalidSelectionOrInactiveOccurrenceDoesNotReplaceState() {
        val state = LimitedRelaxation(file()) { 1_000_000L }
        state.grant(7, "example.a", "A", 2_000_000L)
        assertThrows(IllegalArgumentException::class.java) { state.grant(7, "", "", 2_000_000L) }
        assertThrows(IllegalArgumentException::class.java) { state.grant(7, "example.b", "B", 1_000_000L) }
        assertTrue(state.isRelaxed(7, "example.a"))
    }

    @Test fun corruptedStateAndBackwardClockRestoreBlocking() {
        var now = 1_000_000L
        val state = LimitedRelaxation(file()) { now }
        state.grant(7, "example.a", "A", 2_000_000L)
        now = 999_999L
        assertFalse(state.isRelaxed(7, "example.a"))
        file().writeText("corrupted")
        assertFalse(LimitedRelaxation(file()) { 1_000_000L }.isRelaxed(7, "example.a"))
    }

    @Test fun storageFailureNeverGrantsAnUnpersistedException() {
        val state = LimitedRelaxation(folder.root) { 1_000_000L }
        assertThrows(Exception::class.java) { state.grant(7, "example.a", "A", 2_000_000L) }
        assertFalse(state.isRelaxed(7, "example.a"))
    }

    @Test fun relaxedDecisionSkipsResponseButHostDecisionStaysTrackedAndExpiryRestoresResponse() {
        var now = 1_000_000L
        val state = LimitedRelaxation(file()) { now }
        state.grant(7, "example.a", "A", 3_000_000L)
        val plugin = ForceBlockPlugin(state) { now }
        var overlays = 0
        var feedback = 0
        val capabilities = object : PluginCapabilities {
            override fun overlay() = object : OverlayCapability {
                override fun show(factory: PluginViewFactory, displayMillis: Long, onExpired: Runnable) { overlays++ }
            }
            override fun home(): HomeCapability? = null
            override fun haptic(): HapticCapability? = null
            override fun feedback() = FeedbackCapability { feedback++ }
        }
        val decision = PluginDecision(7, "example.a", "A", true)
        plugin.onDecision(decision, capabilities)
        assertEquals(0, overlays)
        assertEquals(0, feedback)
        assertTrue(decision.shouldTrack)
        now = 1_900_000L
        plugin.onDecision(decision, capabilities)
        assertEquals(1, overlays)
        assertEquals(1, feedback)
    }
}
