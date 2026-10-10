package com.planlock.app.security

import com.planlock.plugin.*
import org.junit.Assert.*
import org.junit.Test

class PluginPermissionAccessTest {
    private val request = PluginContribution.CapabilityRequest("grant", PluginSlots.PLAN_DETAIL_PROTECTION, "测试理由", PluginCapability.ACCESSIBILITY, "grant")
    private fun manifest(capabilities: Set<PluginCapability>, contributions: List<PluginContribution>) = PluginManifest(
        "sample", "Sample", "1.0.0", 1, "sample.Entry", "official", PluginSourceType.OFFICIAL,
        requiredCapabilities = capabilities, contributions = contributions,
    )
    @Test fun declaredSystemRequestHasNeutralTarget() {
        assertEquals(PermissionKind.ACCESSIBILITY, request.capabilityId.permissionKind())
        assertTrue(canRequestPluginPermission(manifest(setOf(PluginCapability.ACCESSIBILITY), listOf(request)), request))
    }
    @Test fun staleOrUndeclaredRequestIsRejected() {
        assertFalse(canRequestPluginPermission(manifest(emptySet(), listOf(request)), request))
        assertFalse(canRequestPluginPermission(manifest(setOf(PluginCapability.ACCESSIBILITY), emptyList()), request))
        assertFalse(canRequestPluginPermission(manifest(setOf(PluginCapability.ACCESSIBILITY), listOf(request)), request.copy(capabilityId = PluginCapability.DND)))
    }
    @Test fun runtimeActionIsNotSystemPermission() {
        assertNull(PluginCapability.OVERLAY.permissionKind())
        assertFalse(canRequestPluginPermission(manifest(setOf(PluginCapability.OVERLAY), listOf(request)), request.copy(capabilityId = PluginCapability.OVERLAY)))
    }
}
