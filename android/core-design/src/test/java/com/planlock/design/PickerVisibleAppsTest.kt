package com.planlock.design

import org.junit.Assert.*
import org.junit.Test

class PickerVisibleAppsTest {
    @Test fun unknownBrowserCategoryStillAppearsInAllAndSearchGroups() {
        val visible = pickerVisibleApps(listOf(AppOption("example.browser", "浏览器", "浏览器"), AppOption("category:x", "分类", "工具")))
        assertEquals(listOf("example.browser"), visible.map { it.id })
        assertTrue(visible.filter { it.name.contains("浏览器") }.all { it.category in StandardAppCategories })
    }
}
