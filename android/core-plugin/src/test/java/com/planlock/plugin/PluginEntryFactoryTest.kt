package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginEntryFactoryTest {
    class Legacy : PlanLockPlugin { override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = Unit }
    class Stored(val directory: File) : PlanLockPlugin { override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = Unit }
    @Test fun legacyNoArgAndScopedFileConstructorAreBothSupported() {
        val root = File("root")
        assertTrue(instantiatePluginEntry(Legacy::class.java, root) is Legacy)
        assertEquals(root, (instantiatePluginEntry(Stored::class.java, root) as Stored).directory)
    }
}
