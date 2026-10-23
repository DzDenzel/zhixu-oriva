package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WeatherCacheTest {
    private class MemoryStore : DataStore<Preferences> {
        private val values = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
    @Test fun manualCityDoesNotDisplayAnOlderCurrentLocationSnapshot() = runBlocking {
        val store = WeatherStore(MemoryStore())
        val manual = WeatherLocation("Shanghai", 31.2, 121.5, "Asia/Shanghai")
        val located = WeatherLocation("Guangzhou", 23.1, 113.3, "Asia/Shanghai")
        store.saveLocation(manual)
        val latest = WeatherSnapshot(located, 29.0, 2, "2026-10-02T12:00", 100)
        store.saveSnapshot(latest)
        assertNull(store.cachedSnapshot())
        assertNull(store.snapshot(manual))
    }
    @Test fun cachedWeatherForTheSelectedManualCityRemainsVisible() = runBlocking {
        val store = WeatherStore(MemoryStore())
        val city = WeatherLocation("Shanghai", 31.2, 121.5, "Asia/Shanghai")
        store.saveLocation(city)
        val latest = WeatherSnapshot(city, 23.0, 2, "2026-10-19T09:00", 100)
        store.saveSnapshot(latest)
        assertEquals(latest, store.cachedSnapshot())
    }
    @Test fun explicitlyClearingLocationAlsoClearsTheDisplayedSnapshot() = runBlocking {
        val store = WeatherStore(MemoryStore())
        val located = WeatherLocation("Guangzhou", 23.1, 113.3, "Asia/Shanghai")
        store.saveSnapshot(WeatherSnapshot(located, 29.0, 2, "2026-10-02T12:00", 100))
        store.clearLocation()
        assertNull(store.cachedSnapshot())
    }
}
