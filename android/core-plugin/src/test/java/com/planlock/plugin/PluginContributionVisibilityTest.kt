package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginContributionVisibilityTest {
    private val request = BoundPluginContribution("sample", "sample", PluginContribution.CapabilityRequest(
        "grant", PluginSlots.PLAN_DETAIL_PROTECTION, "测试理由", PluginCapability.ACCESSIBILITY, "grant",
    ))
    @Test fun permissionOnlySlotIsAbsentAfterGrant() {
        assertTrue(visibleContributions(listOf(request), setOf(PluginCapability.ACCESSIBILITY)).isEmpty())
    }
    @Test fun missingPermissionIsPositiveCase() {
        assertEquals(listOf(request), visibleContributions(listOf(request), emptySet()))
    }
}
