package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlanTimingPreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    @Test fun freshAndExistingPreferencesDefaultOnWithoutOverwritingOtherSettings() = runBlocking {
        val store = MemoryStore()
        store.edit { it[PlanLockPreferenceKeys.basicToolsEnabled] = false }
        assertTrue(PlanTimingPreferences.enabled(store).first())
        assertFalse(store.data.first()[PlanLockPreferenceKeys.basicToolsEnabled]!!)
    }
    @Test fun explicitOffSurvivesNewObserverAndCanBeEnabledAgain() = runBlocking {
        val store = MemoryStore()
        PlanTimingPreferences.setEnabled(store, false)
        assertFalse(PlanTimingPreferences.enabled(store).first())
        PlanTimingPreferences.setEnabled(store, true)
        assertTrue(PlanTimingPreferences.enabled(store).first())
    }
    @Test fun persistenceFailureCannotPublishAnUnstoredValue() = runBlocking {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("disk unavailable")
        }
        try { PlanTimingPreferences.setEnabled(store, false); fail("Expected write failure") } catch (_: IOException) { }
        assertTrue(PlanTimingPreferences.enabled(store).first())
    }
}
