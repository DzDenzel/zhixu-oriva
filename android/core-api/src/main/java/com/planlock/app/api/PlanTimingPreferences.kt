package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Device-local capability preference; existing installations opt in unless explicitly disabled. */
object PlanTimingPreferences {
    fun enabled(store: DataStore<Preferences>) = store.data.map {
        it[PlanLockPreferenceKeys.punctualPlanStart] ?: true
    }.distinctUntilChanged()

    suspend fun setEnabled(store: DataStore<Preferences>, enabled: Boolean) {
        store.edit { it[PlanLockPreferenceKeys.punctualPlanStart] = enabled }
    }
}
