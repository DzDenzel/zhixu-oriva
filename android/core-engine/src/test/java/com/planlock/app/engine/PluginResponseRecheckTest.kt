package com.planlock.app.engine

import com.planlock.plugin.PluginDecision
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PluginResponseRecheckTest {
    @After fun stopEngine() { ExecutionEngine.stop() }
    @Test fun sameForegroundGetsAFreshDecisionAndLeftEndedOrDifferentPlanNeverRespond() {
        val expected = PluginDecision(7, "example.video", "视频", true)
        ExecutionEngine.start(7, emptySet(), emptySet())
        assertEquals(expected, recheckPluginResponse(expected, "example.video"))
        assertNull(recheckPluginResponse(expected, "example.video", interactive = false))
        assertNull(recheckPluginResponse(expected, "example.video", keyguardLocked = true))
        assertNull(recheckPluginResponse(expected, "example.other"))
        ExecutionEngine.start(8, emptySet(), emptySet())
        assertNull(recheckPluginResponse(expected, "example.video"))
        ExecutionEngine.stop()
        assertNull(recheckPluginResponse(expected, "example.video"))
    }
    @Test fun changedHostExemptionCannotBeOverriddenByASavedResponse() {
        val expected = PluginDecision(7, "example.video", "视频", true)
        ExecutionEngine.start(7, emptySet(), setOf("example.video"))
        assertNull(recheckPluginResponse(expected, "example.video"))
    }
}
