package com.planlock.app.security

import org.junit.Assert.*
import org.junit.Test

class ConfirmedAccessibilityHealthTest {
    @Test fun configuredWithoutHeartbeatIsNotConfirmed() {
        assertEquals(AccessibilityHealth.CONFIGURED_NOT_RUNNING, AccessibilityHealthEvaluator.evaluateConfirmed(true, AccessibilityRuntimeSignal(), 100_000))
    }
    @Test fun confirmedHealthUsesActualObservationSourceConditions() {
        assertEquals(AccessibilityHealth.HEALTHY, AccessibilityHealthEvaluator.evaluateConfirmed(true, AccessibilityRuntimeSignal(true, true, 90_000), 100_000))
        assertEquals(AccessibilityHealth.CONFIGURED_NOT_RUNNING, AccessibilityHealthEvaluator.evaluateConfirmed(true, AccessibilityRuntimeSignal(true, true, 10_000), 100_000))
        assertEquals(AccessibilityHealth.CONFIGURED_NOT_RUNNING, AccessibilityHealthEvaluator.evaluateConfirmed(true, AccessibilityRuntimeSignal(true, false, 90_000), 100_000))
        assertEquals(AccessibilityHealth.NOT_CONFIGURED, AccessibilityHealthEvaluator.evaluateConfirmed(false, AccessibilityRuntimeSignal(true, true, 90_000), 100_000))
    }
}
