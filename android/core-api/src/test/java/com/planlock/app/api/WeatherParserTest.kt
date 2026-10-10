package com.planlock.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParserTest {
    @Test
    fun `geocoding parser keeps fields needed for disambiguation`() {
        val json = """{"results":[{"name":"南京","latitude":32.06,"longitude":118.78,"timezone":"Asia/Shanghai","country":"中国","admin1":"江苏"}]}"""

        val result = WeatherParser.parseLocations(json)

        assertEquals(1, result.size)
        assertEquals("南京 · 江苏 · 中国", result.single().displayName)
        assertEquals("Asia/Shanghai", result.single().timezone)
    }

    @Test
    fun `weather parser returns deterministic model`() {
        val location = WeatherLocation("南京 · 江苏 · 中国", 32.06, 118.78, "Asia/Shanghai")
        val json = """{"current":{"time":"2026-09-24T09:30","temperature_2m":23.4,"weather_code":3}}"""

        val result = WeatherParser.parseCurrent(json, location, fetchedAtMillis = 1234L)

        assertEquals(23.4, result?.temperatureC ?: 0.0, 0.001)
        assertEquals(3, result?.weatherCode)
        assertEquals("2026-09-24T09:30", result?.observedAt)
        assertEquals(1234L, result?.fetchedAtMillis)
    }

    @Test
    fun `malformed missing and empty responses degrade without throwing`() {
        val location = WeatherLocation("南京", 32.06, 118.78, "Asia/Shanghai")

        assertTrue(WeatherParser.parseLocations("{" ).isEmpty())
        assertTrue(WeatherParser.parseLocations("{\"results\":[]}").isEmpty())
        assertTrue(WeatherParser.parseLocations("{\"results\":[{\"name\":\"缺字段\"}]}").isEmpty())
        assertNull(WeatherParser.parseCurrent("not-json", location, 1L))
        assertNull(WeatherParser.parseCurrent("{\"current\":{\"temperature_2m\":20}}", location, 1L))
    }
}
