package com.planlock.app.dnd

import android.app.NotificationManager

data class DndState(
    val recordedPlanId: Long? = null,
    val previousFilter: Int? = null,
    val currentFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    val neededPlanId: Long? = null,
    val api35Plus: Boolean = false,
    val hasPolicyAccess: Boolean = true,
    val residualCleanupAttempted: Boolean = false
)

sealed interface DndAction {
    data object PermissionMissing : DndAction
    data object Keep : DndAction
    data object Enable : DndAction
    data class Restore(val filter: Int) : DndAction
    data object Clear : DndAction
    data object Noop : DndAction
}

fun decideDndAction(state: DndState): DndAction = when {
    !state.hasPolicyAccess && (state.recordedPlanId != null || state.neededPlanId != null) -> DndAction.PermissionMissing
    state.recordedPlanId != null && state.neededPlanId == state.recordedPlanId -> {
        if (state.currentFilter == state.previousFilter) DndAction.Enable else DndAction.Keep
    }
    state.recordedPlanId != null && state.neededPlanId == null -> {
        if (state.currentFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS && state.previousFilter != null) DndAction.Restore(state.previousFilter)
        else DndAction.Clear
    }
    state.recordedPlanId == null && state.neededPlanId == null && state.api35Plus && state.hasPolicyAccess && !state.residualCleanupAttempted -> DndAction.Clear
    state.neededPlanId != null -> DndAction.Enable
    else -> DndAction.Noop
}
