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
    private val mutableState = MutableStateFlow(ConnectionState(ConnectionConfig.default))
    val state = mutableState.asStateFlow()

    suspend fun restore(store: DataStore<Preferences>) {
        val prefs = store.data.first()
        val config = if (prefs[localOnlyKey] == true) null else prefs[configKey]?.let(ConnectionConfig::decode) ?: ConnectionConfig.default
        publish(config)
    }

    internal fun publish(config: ConnectionConfig?) {
        val previous = mutableState.value
        if (config != previous.config) mutableState.value = ConnectionState(config, previous.revision + 1)
    }
}
