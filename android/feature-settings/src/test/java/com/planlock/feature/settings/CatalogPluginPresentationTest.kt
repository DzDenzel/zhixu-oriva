package com.planlock.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPluginPresentationTest {
    @Test
    fun `catalog exposes the three runtime states without allowing duplicate install`() {
        val missing = catalogPluginPresentation(isInstalled = false, isEnabled = false)
        val installed = catalogPluginPresentation(isInstalled = true, isEnabled = false)
        val enabled = catalogPluginPresentation(isInstalled = true, isEnabled = true)

        assertEquals("状态：未安装", missing.statusLabel)
        assertEquals("下载并安装", missing.actionLabel)
        assertTrue(missing.installAvailable)

        assertEquals("状态：已安装（未启用）", installed.statusLabel)
        assertEquals("已安装", installed.actionLabel)
        assertFalse(installed.installAvailable)

        assertEquals("状态：已启用", enabled.statusLabel)
        assertEquals("已启用", enabled.actionLabel)
        assertFalse(enabled.installAvailable)
    }
}
