package com.planlock.plugin.forceblocker

import com.planlock.plugin.EmptyPluginCapabilities
import com.planlock.plugin.PluginDecision
import com.planlock.plugin.PluginActionHandler
import com.planlock.plugin.PluginActionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceBlockPluginTest {
    @Test fun declaredExplanationActionActuallyResponds() {
        val plugin = ForceBlockPlugin()
        val reply = (plugin as? PluginActionHandler)?.onAction(PluginActionEvent(7, "explain-blocking"))
        assertTrue("Declared action must explain the policy rather than be an empty callback", reply?.contains("所有分心应用") == true)
    }
    @Test
    fun ignoresHostDecisionThatIsNotTracked() {
        val plugin = ForceBlockPlugin()
        plugin.onDecision(PluginDecision(7, "system", "System", shouldTrack = false), EmptyPluginCapabilities)
    }

    @Test
    fun presentationPreservesMigratedBlockCopy() {
        val presentation = BlockFeedbackPresentation.create("Video", animatorDurationScale = 1f)
        assertEquals("应用已锁定", presentation.title)
        assertEquals("当前计划正在限制此应用", presentation.message)
        assertEquals(900L, presentation.displayMillis)
        assertEquals(BlockFeedbackAnimationTarget.CONTENT, presentation.animationTarget)
    }
}
