package com.planlock.feature.guide

import com.planlock.app.api.dto.AppItem
import com.planlock.design.AppOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GuideWhitelistContractTest {
    private fun payload(apps: List<AppOption>): String = Class.forName("com.planlock.feature.guide.GuideScreenKt")
        .getDeclaredMethod("localList",List::class.java).apply { isAccessible=true }.invoke(null,apps) as String
    @Test fun guideProducesAppItemJsonWithCategoryNameFallback() {
        val value=payload(listOf(AppOption("com.example.reader","Reader","阅读"),AppOption("category:game","全部游戏","游戏")))
        assertTrue("Whitelist must be JSON: $value",value.startsWith("["))
        val items=Json.decodeFromString<List<AppItem>>(value)
        assertEquals(listOf("com.example.reader",""),items.map { it.packageName })
        assertEquals("游戏",items.last().name)
    }
    @Test fun emptyGuideSelectionIsAnEmptyJsonArray() { assertEquals("[]",payload(emptyList())) }
}
