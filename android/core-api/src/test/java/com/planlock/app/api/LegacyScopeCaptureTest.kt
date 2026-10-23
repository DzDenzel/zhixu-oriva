package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegacyScopeCaptureTest {
    @Test fun roomMigrationOwnerIsFrozenBeforeAnotherLoginCanOverwriteIt() = runBlocking {
        val original = ConnectionConfig.default
        val disk = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
                longPreferencesKey("auth_data_owner") to 7L,
                stringPreferencesKey("auth_data_owner_instance") to original.instanceId,
            ))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
        }
        try {
            TokenStore.restore(disk)
            TokenStore.changeConnection(disk, original.copy(apiBaseUrl = "https://migration-other.invalid/api/v1/"))
            TokenStore.save(disk, AuthSession("new-token", 9, 90, signedIn = true))
            assertEquals(original.instanceId, disk.data.value[stringPreferencesKey("auth_legacy_owner_instance_v8")])
            assertEquals(7L, disk.data.value[longPreferencesKey("auth_legacy_owner_user_v8")])
            assertEquals(9L, TokenStore.localDataUserId)
            TokenStore.restore(disk)
            assertEquals(original.instanceId, disk.data.value[stringPreferencesKey("auth_legacy_owner_instance_v8")])
        } finally {
            TokenStore.restore(object : DataStore<Preferences> {
                override val data = MutableStateFlow<Preferences>(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
            })
        }
    }
}
