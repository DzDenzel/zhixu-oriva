package com.planlock.app

import android.content.Context
import com.planlock.design.ThemeMode
import com.planlock.app.api.planLockPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

class ThemePreference(context: Context) {
    private val preferences = context.applicationContext.planLockPreferences
    private val key = stringPreferencesKey("theme_mode")
    val mode = preferences.data.map { ThemeMode.fromValue(it[key]) }
    suspend fun save(value: ThemeMode) { preferences.edit { it[key] = value.value } }
}
