package com.planlock.feature.settings

import com.planlock.plugin.CustomPluginSource
import com.planlock.plugin.PluginCapability
import com.planlock.plugin.PluginSlots
import org.junit.Assert.*
import org.junit.Test

class PluginPresentationTest {
    @Test fun `all runtime capabilities have readable labels without altering IDs`() {
        val ids = PluginCapability.entries.map { it.name }
        assertEquals("覆盖层", pluginCapabilityLabel(PluginCapability.OVERLAY))
        assertEquals("回桌面", pluginCapabilityLabel(PluginCapability.HOME))
        assertEquals("震动", pluginCapabilityLabel(PluginCapability.HAPTIC))
        assertEquals("反馈", pluginCapabilityLabel(PluginCapability.FEEDBACK))
        assertEquals(12, PluginCapability.entries.map(::pluginCapabilityLabel).distinct().size)
        assertEquals(ids, PluginCapability.entries.map { it.name })
    }
    @Test fun `known platforms use familiar names and unknown platforms retain their identity`() {
        assertEquals("Android", devicePlatformLabel("android"))
        assertEquals("iOS", devicePlatformLabel("IOS"))
        assertEquals("custom-platform", devicePlatformLabel("custom-platform"))
        assertEquals("未知平台", devicePlatformLabel(null))
    }
    @Test fun `slot descriptions keep raw identifiers available separately`() {
        assertEquals("计划详情", pluginSlotLabel(PluginSlots.PLAN_DETAIL_PROTECTION))
        assertEquals("计划编辑", pluginSlotLabel(PluginSlots.PLAN_EDIT_CONFIGURATION))
        assertEquals("其他界面位置", pluginSlotLabel("future.slot"))
    }
    @Test fun `optional metadata filters dangerous schemes and formats safe urls`() {
        val safe = formatPluginMetadata(
            author = "  ORIVA Team ",
            license = "MIT",
            homepage = "https://oriva.example.org",
            repository = "javascript:alert(1)",
        )
        assertEquals("ORIVA Team", safe.author)
        assertEquals("MIT", safe.license)
        assertEquals("https://oriva.example.org", safe.homepageUrl)
        assertNull(safe.repositoryUrl)

        val blocked = formatPluginMetadata(
            author = null,
            license = "   ",
            homepage = "data:text/html,hi",
            repository = "file:///etc/passwd",
        )
        assertFalse(blocked.hasAny)
    }
    @Test fun `untrusted custom source messages stay explicit`() {
        val unanchored = CustomPluginSource(
            id = "community-1",
            name = "Community",
            catalogUrl = "https://community.example.org/catalog.json",
        )
        assertTrue(customSourceTrustLabel(unanchored).contains("未配置"))
        assertTrue(formatPluginInstallError("No trust anchors registered for source community-1").contains("不会跳过签名校验"))
    }
}
