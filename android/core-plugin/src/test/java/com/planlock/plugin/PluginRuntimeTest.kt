package com.planlock.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginRuntimeTest {
    @Test
    fun executablePluginArtifactIsSealedReadOnlyBeforeRuntimeLoading() {
        val artifact = File.createTempFile("planlock-plugin", ".plp").apply {
            writeText("plugin")
            deleteOnExit()
        }

        ReadOnlyPluginArtifact.seal(artifact)

        assertTrue(artifact.canRead())
        assertFalse(artifact.canWrite())
    }

    @Test
    fun manifestDeclaresGenericEntryPointSourceAndCapabilities() {
        val manifest = PluginManifestCodec.decode(
            """{"id":"sample","name":"Sample","version":"1.2.0","contractVersion":1,"entryPoint":"sample.Entry","sourceId":"official","sourceType":"OFFICIAL","requiredCapabilities":["OVERLAY","HOME"]}"""
        )

        assertEquals("sample", manifest.id)
        assertEquals("sample.Entry", manifest.entryPoint)
        assertEquals(PluginSourceType.OFFICIAL, manifest.sourceType)
        assertEquals(setOf(PluginCapability.OVERLAY, PluginCapability.HOME), manifest.requiredCapabilities)
    }

    @Test
    fun lifecycleSeparatesInstallEnableAndGlobalSwitch() {
        assertEquals(PluginInstallState.UNINSTALLED, PluginLifecycle.state(installed = false, enabled = false))
        assertEquals(PluginInstallState.INSTALLED, PluginLifecycle.state(installed = true, enabled = false))
        assertEquals(PluginInstallState.ENABLED, PluginLifecycle.state(installed = true, enabled = true))
        assertFalse(PluginLifecycle.isEffective(globalEnabled = false, installed = true, enabled = true, sourceType = PluginSourceType.OFFICIAL, safeMode = true))
        assertTrue(PluginLifecycle.isEffective(globalEnabled = true, installed = true, enabled = true, sourceType = PluginSourceType.OFFICIAL, safeMode = true))
        assertFalse(PluginLifecycle.isEffective(globalEnabled = true, installed = true, enabled = true, sourceType = PluginSourceType.THIRD_PARTY, safeMode = true))
        assertTrue(PluginLifecycle.isEffective(globalEnabled = true, installed = true, enabled = true, sourceType = PluginSourceType.THIRD_PARTY, safeMode = false))
    }

    @Test
    fun capabilityPolicyGrantsOnlyDeclaredAndRegisteredCapabilities() {
        val result = CapabilityPolicy.evaluate(
            requested = setOf(PluginCapability.OVERLAY, PluginCapability.HOME),
            registered = setOf(PluginCapability.HOME, PluginCapability.HAPTIC),
        )

        assertEquals(setOf(PluginCapability.HOME), result.granted)
        assertEquals(setOf(PluginCapability.OVERLAY), result.missing)
    }

    @Test
    fun undeclaredRuntimeCapabilityIsNotExposedToPlugin() {
        val raw = object : PluginCapabilities {
            override fun overlay(): OverlayCapability? = null
            override fun home(): HomeCapability = HomeCapability { }
            override fun haptic(): HapticCapability = HapticCapability { }
            override fun feedback(): FeedbackCapability? = null
        }

        val restricted = DeclaredPluginCapabilities(setOf(PluginCapability.HOME), raw)

        assertTrue(restricted.home() != null)
        assertTrue(restricted.haptic() == null)
    }

    @Test
    fun crashingPluginIsContainedAndLaterPluginsStillRun() {
        val calls = mutableListOf<String>()
        val crashing = loaded("crashing") { error("boom") }
        val healthy = loaded("healthy") { calls += it.packageName }

        val results = PluginDispatcher().dispatch(
            plugins = listOf(crashing, healthy),
            decision = PluginDecision(7, "video", "Video", shouldTrack = true),
            capabilities = EmptyPluginCapabilities,
        )

        assertEquals(listOf("video"), calls)
        assertEquals(PluginDispatchStatus.FAILED_OPEN, results.first().status)
        assertEquals(PluginDispatchStatus.EXECUTED, results.last().status)
    }

    private fun loaded(id: String, action: (PluginDecision) -> Unit) = LoadedPlugin(
        manifest = PluginManifest(id, id, "1", 1, "$id.Entry", "official", PluginSourceType.OFFICIAL, emptySet()),
        instance = object : PlanLockPlugin {
            override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = action(decision)
        },
    )
}
