package com.planlock.app.security

import org.junit.Assert.*
import org.junit.Test

class BasePermissionSummaryTest {
    @Test fun fullyGrantedHostHasFourPermissionsEvenWhenAccessibilityIsDisabled() {
        val states = basePermissionKinds().associateWith { true } + (PermissionKind.ACCESSIBILITY to false)
        assertEquals(BasePermissionSummary(4, 4), basePermissionSummary(states))
    }
    @Test fun pluginAuthorizationDoesNotAffectHostSummary() {
        val host = basePermissionKinds().associateWith { true }
        assertEquals(basePermissionSummary(host + (PermissionKind.ACCESSIBILITY to false)), basePermissionSummary(host + (PermissionKind.ACCESSIBILITY to true)))
    }
    @Test fun deniedHostPermissionAndUnknownStatusRemainInDenominator() {
        val host = basePermissionKinds().associateWith { true }.toMutableMap()
        host[PermissionKind.DND] = false
        assertEquals(BasePermissionSummary(3, 4), basePermissionSummary(host))
        assertEquals(BasePermissionSummary(0, 4), basePermissionSummary(emptyMap()))
    }
}
