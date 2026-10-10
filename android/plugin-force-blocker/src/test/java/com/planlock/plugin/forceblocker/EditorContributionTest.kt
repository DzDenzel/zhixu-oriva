package com.planlock.plugin.forceblocker

import com.planlock.plugin.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Consume the shipping manifests through the real projection and action dispatcher. */
class EditorContributionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun editorContributionRunsTheExistingSingleAppExceptionAndUninstallRemovesIt() {
        for (filename in listOf("plugin.json", "plugin-third-party.json")) {
            val manifest = PluginManifestCodec.decode(File(filename).readText(Charsets.UTF_8))
            var now = 1_000_000L
            val state = LimitedRelaxation(File(folder.root, "$filename.properties")) { now }
            val plugin = ForceBlockPlugin(state) { now }
            val loaded = listOf(LoadedPlugin(manifest, plugin))
            val editor = projectContributions(loaded, PluginSlots.PLAN_EDIT_CONFIGURATION)
            assertTrue("Shipping plugin must contribute its all-block explanation to the editor", editor.any {
                it.contribution is PluginContribution.StatusLine
            })
            val action = editor.map { it.contribution }.filterIsInstance<PluginContribution.ActionRow>()
                .single { it.input == ContributionActionInput.APPLICATION_SINGLE }
            val result = dispatchContributionAction(loaded, manifest.id,
                PluginActionEvent(-42, action.actionId, "example.video", "视频", 4_000_000L))
            assertEquals(PluginActionStatus.EXECUTED, result.status)
            assertEquals(900_000L, plugin.responseDelayMillis(PluginDecision(-42, "example.video", "视频", true)))
            assertNull(plugin.responseDelayMillis(PluginDecision(-42, "example.other", "其它", true)))
            now += 900_000L
            assertNull(plugin.responseDelayMillis(PluginDecision(-42, "example.video", "视频", true)))
            assertTrue(projectContributions(emptyList(), PluginSlots.PLAN_EDIT_CONFIGURATION).isEmpty())
            assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(emptyList(), manifest.id,
                PluginActionEvent(-42, action.actionId, "example.video", "视频", 4_000_000L)).status)
        }
    }
}
