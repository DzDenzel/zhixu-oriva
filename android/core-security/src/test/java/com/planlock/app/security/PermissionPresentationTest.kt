package com.planlock.app.security

import org.junit.Assert.*
import org.junit.Test

class PermissionPresentationTest {
    @Test fun baseSettingsDoNotOfferPluginOwnedAccessibility() {
        assertFalse(PermissionKind.ACCESSIBILITY in basePermissionKinds())
        assertTrue(PermissionKind.USAGE_STATS in basePermissionKinds())
        assertTrue(PermissionKind.NOTIFICATIONS in basePermissionKinds())
    }
    @Test fun noGrantMeansNoObservableApplicationRecords() {
        assertEquals(ObservationAvailability.UNAVAILABLE, observationAvailability(AccessibilityHealth.NOT_CONFIGURED, false))
    }
    @Test fun usageGrantMakesApproximationExplicit() {
        assertEquals(ObservationAvailability.APPROXIMATE, observationAvailability(AccessibilityHealth.NOT_CONFIGURED, true))
        assertEquals(ObservationAvailability.APPROXIMATE, observationAvailability(AccessibilityHealth.CONFIGURED_NOT_RUNNING, true))
    }
    @Test fun configuredButStoppedIsNotPrecise() {
        assertEquals(ObservationAvailability.UNAVAILABLE, observationAvailability(AccessibilityHealth.CONFIGURED_NOT_RUNNING, false))
    }
    @Test fun healthyAccessibilityIsPrecise() {
        assertEquals(ObservationAvailability.PRECISE, observationAvailability(AccessibilityHealth.HEALTHY, false))
    }
}
