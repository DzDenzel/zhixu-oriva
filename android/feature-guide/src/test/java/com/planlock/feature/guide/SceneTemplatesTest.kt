package com.planlock.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTemplatesTest {
    @Test
    fun templatesContainNoRestrictionOrStrictModeConfiguration() {
        assertEquals(listOf("词典", "笔记", "浏览器", "音乐"), SceneTemplates.STUDY.excludedCategories)
        assertTrue(SceneTemplates.SLEEP.excludedCategories.isEmpty())
    }
}
