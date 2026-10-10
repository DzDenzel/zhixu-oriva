package com.planlock.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherLocationResolverTest {
    private val located = WeatherLocation("当前位置", 23.1, 113.3, "auto")
    private val manual = WeatherLocation("广州 · 广东 · 中国", 23.12, 113.26, "Asia/Shanghai")

    @Test
    fun `located source wins then falls back to manual or none`() {
        assertEquals(located, WeatherLocationChoice.select(located, manual))
        assertEquals(manual, WeatherLocationChoice.select(null, manual))
        assertNull(WeatherLocationChoice.select(null, null))
    }

    @Test
    fun `display name uses locality hierarchy and degrades safely`() {
        assertEquals("广州 · 广东 · 中国", WeatherLocationNaming.displayName("广州", null, "广东", "中国"))
        assertEquals("海珠区 · 广东 · 中国", WeatherLocationNaming.displayName(null, "海珠区", "广东", "中国"))
        assertEquals("当前位置", WeatherLocationNaming.displayName(null, null, null, null))
    }
}
