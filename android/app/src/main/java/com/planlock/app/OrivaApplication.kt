package com.planlock.app

import android.app.Application
import com.planlock.app.api.TokenStore
import com.planlock.app.sync.AppSync
import com.planlock.design.BackdropStore
import com.planlock.design.ThemeStyleStore
import com.planlock.plugin.PluginConfigurationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wire app-owned settings to the sync transaction boundary without module dependency inversion. */
class OrivaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore visual preferences synchronously before any entry point can compose its first frame.
        BackdropStore.load(this)
        ThemeStyleStore.load(this)
        AppSync.get(this).beforePlanMapping = { old, new ->
            withContext(Dispatchers.IO) {
                val owner = TokenStore.localDataUserId
                check(TokenStore.canSync && owner != null)
                PluginConfigurationStore(this@OrivaApplication).movePlanDurably(owner, old, new, adoptUnownedDraft = true)
            }
        }
    }
}
