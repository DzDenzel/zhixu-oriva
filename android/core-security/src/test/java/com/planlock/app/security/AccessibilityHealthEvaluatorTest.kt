package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityHealthEvaluatorTest {
    @Test
    fun `not configured is unavailable`() {
        assertEquals(
            AccessibilityHealth.NOT_CONFIGURED,
            AccessibilityHealthEvaluator.evaluate(false, AccessibilityRuntimeSignal(), nowMillis = 100_000)
        )
    }

    @Test
    fun `configured without any service history uses startup fallback`() {
        assertEquals(
            AccessibilityHealth.HEALTHY,
            AccessibilityHealthEvaluator.evaluate(true, AccessibilityRuntimeSignal(), nowMillis = 100_000)
        )
    }

    @Test
    fun `fresh running heartbeat is healthy`() {
        val signal = AccessibilityRuntimeSignal(hasEverReported = true, running = true, heartbeatAtMillis = 80_000)
        assertEquals(
            AccessibilityHealth.HEALTHY,
            AccessibilityHealthEvaluator.evaluate(true, signal, nowMillis = 100_000)
        )
    }

    @Test
    fun `stopped or stale service is configured but unhealthy`() {
        val stopped = AccessibilityRuntimeSignal(hasEverReported = true, running = false, heartbeatAtMillis = 99_000)
        val stale = AccessibilityRuntimeSignal(hasEverReported = true, running = true, heartbeatAtMillis = 50_000)

        assertEquals(AccessibilityHealth.CONFIGURED_NOT_RUNNING, AccessibilityHealthEvaluator.evaluate(true, stopped, 100_000))
        assertEquals(AccessibilityHealth.CONFIGURED_NOT_RUNNING, AccessibilityHealthEvaluator.evaluate(true, stale, 100_000))
    }
}
