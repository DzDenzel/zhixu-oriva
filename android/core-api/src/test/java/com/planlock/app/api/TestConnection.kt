package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/** Explicit test precondition: authentication requires an imported connection in every variant. */
internal suspend fun importTestConnection(store: DataStore<Preferences>) {
    ConnectionStore.restore(store)
    TokenStore.restore(store)
    TokenStore.changeConnection(store, ConnectionConfig(
        instanceLabel = "测试后端",
        apiBaseUrl = "https://example.invalid/api/v1/",
        pluginsCatalogUrl = "https://example.invalid/plugins/catalog/",
    ))
}
