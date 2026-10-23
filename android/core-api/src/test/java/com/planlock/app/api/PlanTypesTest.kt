package com.planlock.app.api

import org.junit.Assert.*
import org.junit.Test

class PlanTypesTest {
    @Test fun legacyValuesAndUnknownTypesHaveStableDisplayWithoutRewritingStoredValues() {
        val inputs = listOf("学习", "工作", "运动", "睡眠", "study", "work", "sport", "sleep", "reading", "rest", "life", "hobby", "unrecognized")
        assertEquals(listOf("学习", "工作", "运动", "休息", "学习", "工作", "运动", "休息", "阅读", "休息", "生活", "兴趣", "其他"), inputs.map { PlanTypes.label(it) })
        assertEquals(8, PlanTypes.presets.size)
        assertEquals(8, PlanTypes.colors.size)
        assertEquals(8, PlanTypes.presets.map { it.color }.distinct().size)
    }

    @Test fun customLabelsAreResolvedOnlyFromProvidedAccountCatalog() {
        val custom = PlanTypeDefinition("custom:123", "写作", "rose")
        assertEquals("写作", PlanTypes.label("custom:123", listOf(custom)))
        assertEquals("其他", PlanTypes.label("custom:123"))
        assertEquals("其他", PlanTypes.label("custom:123", listOf(custom.copy(deleted = true))))
    }

    @Test fun namesCountUnicodeCharactersAndRejectEmptyLongOrControlInput() {
        assertEquals("写作", PlanTypes.validateName(" 写作 "))
        assertEquals("😀😀😀😀😀😀", PlanTypes.validateName("😀😀😀😀😀😀"))
        listOf("", "  ", "一二三四五六七", "学\n习").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { PlanTypes.validateName(input) }
        }
    }
}
