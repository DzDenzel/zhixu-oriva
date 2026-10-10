package com.planlock.design

import com.planlock.plugin.*
import org.junit.Assert.*
import org.junit.Test

class PluginPermissionPromptTest {
    private val request = PluginContribution.CapabilityRequest("grant", PluginSlots.PLAN_DETAIL_PROTECTION,
        "用于免打扰", PluginCapability.DND, "grant")
    private val manifest = PluginManifest("sample", "Sample", "1.0.0", 1, "sample.Entry", "official",
        PluginSourceType.OFFICIAL, requiredCapabilities = setOf(PluginCapability.DND), contributions = listOf(request))

    @Test fun cancelledExplanationNeverLaunchesSettings() {
        val prompt = PluginPermissionPrompt { emptySet() }
        var launched = 0
        prompt.request(manifest, request)
        assertNotNull(prompt.pending)
        prompt.dismiss()
        prompt.confirm { _, _ -> launched++ }
        assertNull(prompt.pending)
        assertEquals(0, launched)
    }

    @Test fun alreadyGrantedCapabilityDoesNotPrompt() {
        val prompt = PluginPermissionPrompt { setOf(PluginCapability.DND) }
        prompt.request(manifest, request)
        assertNull(prompt.pending)
    }

    @Test fun grantArrivingDuringExplanationPreventsRepeatRequest() {
        var granted = emptySet<PluginCapability>()
        val prompt = PluginPermissionPrompt { granted }
        var launched = 0
        prompt.request(manifest, request)
        granted = setOf(PluginCapability.DND)
        prompt.confirm { _, _ -> launched++ }
        assertEquals(0, launched)
        assertNull(prompt.pending)
    }

    @Test fun explicitConfirmationLaunchesOnce() {
        val prompt = PluginPermissionPrompt { emptySet() }
        var launched = 0
        prompt.request(manifest, request)
        prompt.confirm { actualManifest, actualRequest ->
            assertEquals("sample", actualManifest.id)
            assertEquals(PluginCapability.DND, actualRequest.capabilityId)
            launched++
        }
        prompt.confirm { _, _ -> launched++ }
        assertEquals(1, launched)
    }
}
