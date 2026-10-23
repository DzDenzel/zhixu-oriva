package com.planlock.feature.plan

import com.planlock.app.api.TokenStore
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.record.RecordStore
import com.planlock.app.record.ScheduleReminderScheduler
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.CancellationException

/** The only confirmed schedule-save entry used by Today, Plans and Assistant. */
suspend fun saveSchedule(
    store: RecordStore,
    reminders: ScheduleReminderScheduler,
    sync: AppSync,
    request: ScheduleUpsertRequest,
    current: ScheduleSnapshotEntity? = null,
) {
    val localId = current?.id ?: -System.currentTimeMillis()
    val saved = ScheduleSnapshotEntity(
        localId, scheduleWorkspaceOwner(current?.userId, TokenStore.state.value),
        request.title, request.startDate, request.startTime, request.endDate, request.endTime,
        request.allDay, request.location, request.note, request.remindAdvanceMin, request.repeatRule,
        request.linkedPlanId, false, request.tzId, 1, current?.version ?: 0, scope = current?.scope ?: store.currentScope(),
        locationLat = request.locationLat, locationLon = request.locationLon,
    )
    sync.withLocalMutation {
        check(saved.scope == store.currentScope()) { "账号已变化，请重新打开编辑页" }
        store.saveScheduleChange(saved, request)
        store.schedule(localId)?.let { reminders.schedule(it) }
    }
    sync.localChangesCommitted()
}

internal fun scheduleWorkspaceOwner(existingOwner: Long?, auth: com.planlock.app.api.AuthState): Long =
    auth.localDataUserId ?: 0L

suspend fun deleteSchedule(store: RecordStore, reminders: ScheduleReminderScheduler, sync: AppSync, current: ScheduleSnapshotEntity) {
    sync.withLocalMutation {
        store.enqueueScheduleDelete(current.id, current.scope)
        reminders.cancel(current.id)
    }
    sync.localChangesCommitted()
}
