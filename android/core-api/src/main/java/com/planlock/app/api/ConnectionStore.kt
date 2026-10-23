package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class ConnectionState(val config: ConnectionConfig?, val revision: Long = 0) {
    val instanceId: String? get() = config?.instanceId
}

/** Process state is published only after the DataStore transaction commits. */
object ConnectionStore {
    internal val configKey = stringPreferencesKey("backend_connection_v1")
    internal val localOnlyKey = booleanPreferencesKey("backend_local_only")
    private val mutableState = MutableStateFlow(ConnectionState(ConnectionConfig.initial))
    val state = mutableState.asStateFlow()

    suspend fun restore(store: DataStore<Preferences>) {
        val prefs = store.data.first()
        val config = restoredConfig(prefs, ConnectionConfig.initial)
        publish(config)
    }

    internal fun restoredConfig(prefs: Preferences, initial: ConnectionConfig?): ConnectionConfig? = when {
        prefs[localOnlyKey] == true -> null
        prefs[configKey] != null -> ConnectionConfig.decode(requireNotNull(prefs[configKey]))
        // A persisted device session proves the implicit default once connected successfully.
        !prefs[stringPreferencesKey("token")].isNullOrBlank() && prefs[longPreferencesKey("user_id")] != null &&
            prefs[longPreferencesKey("device_id")] != null -> initial ?: ConnectionConfig.legacyDefault()
        else -> initial
    }

    suspend fun needsChoice(store: DataStore<Preferences>): Boolean {
        val prefs = store.data.first()
        return state.value.config == null && prefs[localOnlyKey] != true
    }

    internal fun publish(config: ConnectionConfig?) {
        val previous = mutableState.value
        if (config != previous.config) mutableState.value = ConnectionState(config, previous.revision + 1)
    }
}
