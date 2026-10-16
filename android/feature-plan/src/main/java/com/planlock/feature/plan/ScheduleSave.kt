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
        request.linkedPlanId, false, request.tzId, 1, current?.version ?: 0,
    )
    store.saveScheduleChange(saved, request)
    reminders.schedule(saved)
    try { sync.pushPendingRecords() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Persisted local data and pending work remain available offline. */ }
    if (shouldCancelProvisionalReminder(localId, store.resolveScheduleId(localId) == localId)) reminders.cancel(localId)
}

internal fun scheduleWorkspaceOwner(existingOwner: Long?, auth: com.planlock.app.api.AuthState): Long =
    existingOwner ?: auth.localDataUserId ?: 1L
