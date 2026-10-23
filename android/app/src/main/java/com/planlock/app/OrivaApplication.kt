package com.planlock.app

import android.app.Application
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.sync.AppSync
import com.planlock.design.BackdropStore
import com.planlock.design.ThemeStyleStore
import com.planlock.plugin.PluginConfigurationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Wire app-owned settings to the sync transaction boundary without module dependency inversion. */
class OrivaApplication : Application() {
    private val executionWatch = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        com.planlock.app.api.HostApplication.packageName = packageName
        com.planlock.app.security.PlanReminderChannel.ensure(this)
        NotificationSilenceController.initialize(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { AppVisibility.startedActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) { AppVisibility.startedActivities = (AppVisibility.startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) { NotificationSilenceController.refreshConnection() }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // Restore visual preferences synchronously before any entry point can compose its first frame.
        BackdropStore.load(this)
        ThemeStyleStore.load(this)
        val configurations = PluginConfigurationStore(this)
        AppSync.get(this).beforePlanMapping = { old, new ->
            withContext(Dispatchers.IO) {
                configurations.registerLegacyOwner(TokenStore.legacyOwnerScope(planLockPreferences.data.first()))
                check(TokenStore.canSync && TokenStore.localDataUserId != null)
                configurations.movePlanDurably(TokenStore.state.value.dataScope, old, new, adoptUnownedDraft = true)
            }
        }
        AppSync.get(this).afterGuestMerge = { sources, destination ->
            withContext(Dispatchers.IO) {
                configurations.registerLegacyOwner(TokenStore.legacyOwnerScope(planLockPreferences.data.first()))
                sources.forEach { configurations.moveScope(it, destination) }
            }
        }
        // Saving/disabling a currently occurring plan must work while the idle service is absent.
        executionWatch.launch {
            val store = com.planlock.app.record.RecordStore(this@OrivaApplication)
            combine(TokenStore.state.map { it.dataScope }.distinctUntilChanged(), store.localRevision) { _, _ -> Unit }
                .collect {
                    try { AppSync.get(this@OrivaApplication).withLocalMutation { ExecutionService.reconcile(this@OrivaApplication) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.w("OrivaExecution", "Execution state refresh failed", error) }
                }
        }
    }
}
