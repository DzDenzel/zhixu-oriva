package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test

class TimedPluginResponseTest {
    private class ResponsePlugin : PlanLockPlugin, TimedPluginResponse {
        var relaxed = true
        var blocks = 0
        override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) { if (!relaxed && decision.shouldTrack) blocks++ }
        override fun responseDelayMillis(decision: PluginDecision): Long? = if (relaxed && decision.shouldTrack) 900_000L else null
    }
    private class Scheduler : PluginResponseScheduler {
        var callback: ((PluginDecision) -> Unit)? = null
        var delay: Long? = null
        override fun schedule(expected: PluginDecision, delayMillis: Long, onReady: (PluginDecision) -> Unit): AutoCloseable {
            delay = delayMillis; callback = onReady
            return AutoCloseable { callback = null }
        }
    }
    private fun load(plugin: ResponsePlugin) = LoadedPlugin(PluginManifest("sample", "Sample", "1", 1, "sample.Entry", "official", PluginSourceType.OFFICIAL), plugin)
    @Test fun expiryRespondsWithoutAnotherWindowEventUsingCurrentlyLoadedInstance() {
        val old = ResponsePlugin()
        val current = ResponsePlugin().apply { relaxed = false }
        val scheduler = Scheduler()
        val decision = PluginDecision(7, "example.video", "视频", true)
        try {
            PluginRuntimeRegistry.setResponseScheduler(scheduler)
            PluginRuntimeRegistry.replace(listOf(load(old)))
            PluginRuntimeRegistry.dispatch(decision, EmptyPluginCapabilities)
            assertEquals(900_000L, scheduler.delay)
            val ready = checkNotNull(scheduler.callback)
            PluginRuntimeRegistry.replace(listOf(load(current)))
            ready(decision)
            assertEquals(0, old.blocks)
            assertEquals(1, current.blocks)
        } finally { PluginRuntimeRegistry.setResponseScheduler(null); PluginRuntimeRegistry.replace(emptyList()) }
    }
    @Test fun disabledPluginAndStoppedServiceCannotReceiveASavedTimerCallback() {
        val plugin = ResponsePlugin()
        val scheduler = Scheduler()
        val decision = PluginDecision(7, "example.video", "视频", true)
        try {
            PluginRuntimeRegistry.setResponseScheduler(scheduler)
            PluginRuntimeRegistry.replace(listOf(load(plugin)))
            PluginRuntimeRegistry.dispatch(decision, EmptyPluginCapabilities)
            val ready = checkNotNull(scheduler.callback)
            PluginRuntimeRegistry.replace(emptyList())
            plugin.relaxed = false
            ready(decision)
            assertEquals(0, plugin.blocks)
            assertNull(scheduler.callback)
            PluginRuntimeRegistry.replace(listOf(load(plugin.apply { relaxed = true })))
            PluginRuntimeRegistry.dispatch(decision, EmptyPluginCapabilities)
            val afterStop = checkNotNull(scheduler.callback)
            PluginRuntimeRegistry.setResponseScheduler(null)
            plugin.relaxed = false
            afterStop(decision)
            assertEquals(0, plugin.blocks)
        } finally { PluginRuntimeRegistry.setResponseScheduler(null); PluginRuntimeRegistry.replace(emptyList()) }
    }
}
