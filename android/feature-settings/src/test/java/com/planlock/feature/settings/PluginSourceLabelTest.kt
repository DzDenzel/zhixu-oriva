package com.planlock.feature.settings

import com.planlock.plugin.PluginSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginSourceLabelTest {
    @Test
    fun `official plugin source is explicit`() {
        assertEquals("来源：官方", pluginSourceLabel(PluginSourceType.OFFICIAL))
    }

    @Test
    fun `third party plugin source is explicit`() {
        assertEquals("来源：第三方", pluginSourceLabel(PluginSourceType.THIRD_PARTY))
    }
}
