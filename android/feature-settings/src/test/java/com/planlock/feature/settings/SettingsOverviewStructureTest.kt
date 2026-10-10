package com.planlock.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOverviewStructureTest {
    @Test
    fun `overview keeps approved sections and exposes every domain exactly once`() {
        val sections = SettingsOverviewStructure.sections

        assertEquals(listOf("外观", "功能", "数据与账户", "安全与系统"), sections.map { it.title })

        val items = sections.flatMap { it.items }
        assertEquals(SettingsOverviewItem.entries.toList(), items)
        assertEquals(8, items.distinct().size)
        assertTrue(sections.all { it.items.isNotEmpty() })
    }
}
