package com.planlock.app.engine

import com.planlock.app.record.ObservationSource
import com.planlock.app.security.AccessibilityRuntimeSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationSourcePolicyTest {
    @Test
    fun healthyAccessibilityNeverRunsUsageStatsInParallel() {
        val signal = AccessibilityRuntimeSignal(true, running = true, heartbeatAtMillis = 90_000)
        assertEquals(ObservationSource.ACCESSIBILITY, ObservationSourcePolicy.select(signal, 100_000))
    }

    @Test
    fun missingStoppedOrStaleServiceUsesApproximateFallback() {
        assertEquals(ObservationSource.USAGE_STATS_APPROXIMATE, ObservationSourcePolicy.select(AccessibilityRuntimeSignal(), 100_000))
        assertEquals(ObservationSource.USAGE_STATS_APPROXIMATE, ObservationSourcePolicy.select(AccessibilityRuntimeSignal(true, false, 90_000), 100_000))
        assertEquals(ObservationSource.USAGE_STATS_APPROXIMATE, ObservationSourcePolicy.select(AccessibilityRuntimeSignal(true, true, 1_000), 100_000))
    }
}
