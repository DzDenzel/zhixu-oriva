package com.planlock.app.dnd

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.planLockPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

sealed interface DndResult {
    data object Noop : DndResult
    data object Kept : DndResult
    data object Applied : DndResult
    data object Restored : DndResult
    data object Cleared : DndResult
    data object PermissionMissing : DndResult
}

/** Crash-safe notification policy controller; the durable record is write-ahead. */
class DndController(private val context: Context) {
    private val preferences = context.planLockPreferences
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var residualCleanupAttempted = false

    suspend fun reconcile(neededPlanId: Long?): DndResult {
        val record = readRecord()
        val hasPolicyAccess = hasPolicyAccess()
        if (!hasPolicyAccess) residualCleanupAttempted = false
        if (record != null && record.planId != neededPlanId && neededPlanId != null) {
            restoreRecord(record)
            clearRecord()
            return enableNew(neededPlanId)
        }

        val decision = decideDndAction(
            DndState(
                recordedPlanId = record?.planId,
                previousFilter = record?.previousFilter,
                currentFilter = notificationManager.currentInterruptionFilter,
                neededPlanId = neededPlanId,
                api35Plus = Build.VERSION.SDK_INT >= 35,
                hasPolicyAccess = hasPolicyAccess,
                residualCleanupAttempted = residualCleanupAttempted
            )
        )
        return when (decision) {
            DndAction.PermissionMissing -> DndResult.PermissionMissing
            DndAction.Keep -> DndResult.Kept
            DndAction.Enable -> if (record != null) reapply() else enableNew(checkNotNull(neededPlanId))
            is DndAction.Restore -> {
                if (!hasPolicyAccess()) return DndResult.PermissionMissing
                setFilter(decision.filter)
                clearRecord()
                DndResult.Restored
            }
            DndAction.Clear -> {
                // A changed filter belongs to the user. Only clear a residual
                // app-owned rule when there is no write-ahead record.
                if (record == null && hasPolicyAccess) {
                    try {
                        setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                        residualCleanupAttempted = true
                    } catch (_: SecurityException) {
                        return DndResult.PermissionMissing
                    }
                }
                clearRecord()
                DndResult.Cleared
            }
            DndAction.Noop -> DndResult.Noop
        }
    }

    fun reconcileBlocking(neededPlanId: Long?): DndResult = runBlocking { reconcile(neededPlanId) }

    private suspend fun enableNew(planId: Long): DndResult {
        if (!hasPolicyAccess()) return DndResult.PermissionMissing
        writeRecord(DndRecord(planId, notificationManager.currentInterruptionFilter, System.currentTimeMillis()))
        return try {
            setFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            DndResult.Applied
        } catch (_: SecurityException) {
            DndResult.PermissionMissing
        }
    }

    private suspend fun reapply(): DndResult {
        if (!hasPolicyAccess()) return DndResult.PermissionMissing
        return try {
            setFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            DndResult.Applied
        } catch (_: SecurityException) {
            DndResult.PermissionMissing
        }
    }

    private suspend fun restoreRecord(record: DndRecord) {
        if (!hasPolicyAccess()) throw SecurityException("Notification policy access missing")
        if (Build.VERSION.SDK_INT >= 35) {
            setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } else if (notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
            setFilter(record.previousFilter)
        }
    }

    private fun hasPolicyAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    private fun setFilter(filter: Int) {
        notificationManager.setInterruptionFilter(filter)
    }

    private data class DndRecord(val planId: Long, val previousFilter: Int, val appliedAt: Long)

    private suspend fun readRecord(): DndRecord? {
        val p = preferences.data.first()
        val planId = p[PlanLockPreferenceKeys.dndPlanId] ?: return null
        val previous = p[PlanLockPreferenceKeys.dndPreviousFilter] ?: return null
        return DndRecord(planId, previous, p[PlanLockPreferenceKeys.dndAppliedAt] ?: 0L)
    }

    private suspend fun writeRecord(record: DndRecord) {
        preferences.edit { p ->
            p[PlanLockPreferenceKeys.dndPreviousFilter] = record.previousFilter
            p[PlanLockPreferenceKeys.dndPlanId] = record.planId
            p[PlanLockPreferenceKeys.dndAppliedAt] = record.appliedAt
        }
    }

    private suspend fun clearRecord() {
        preferences.edit { p ->
            p.remove(PlanLockPreferenceKeys.dndPreviousFilter)
            p.remove(PlanLockPreferenceKeys.dndPlanId)
            p.remove(PlanLockPreferenceKeys.dndAppliedAt)
        }
    }
}
