package com.planlock.app.api

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class DefaultConnectionPolicyTest {
    @Test fun releaseFreshAndUnsuccessfulLegacyDefaultStayUnconnected() {
        assertNull(ConnectionStore.restoredConfig(emptyPreferences(), null))
        assertNull(ConnectionStore.restoredConfig(preferencesOf(longPreferencesKey("auth_data_owner") to 7L), null))
    }
    @Test fun explicitImportedConfigAndSuccessfulLegacySessionArePreserved() {
        val imported = ConnectionConfig(instanceLabel = "我的后端", apiBaseUrl = "https://example.invalid/api/v1/", pluginsCatalogUrl = "https://example.invalid/plugins/")
        assertEquals(imported, ConnectionStore.restoredConfig(preferencesOf(ConnectionStore.configKey to imported.encode()), null))
        val legacy = preferencesOf(stringPreferencesKey("token") to "registered", longPreferencesKey("user_id") to 7L, longPreferencesKey("device_id") to 8L)
        assertEquals(ConnectionConfig.legacyInstanceId, ConnectionStore.restoredConfig(legacy, null)?.instanceId)
        assertNull(ConnectionStore.restoredConfig(legacy.toMutablePreferences().apply { this[ConnectionStore.localOnlyKey] = true }, null))
    }
}
