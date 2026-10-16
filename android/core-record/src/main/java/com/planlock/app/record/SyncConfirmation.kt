package com.planlock.app.record

import com.planlock.app.api.dto.*
import com.planlock.app.record.entity.*
import kotlinx.serialization.Serializable

/** A persisted choice captures both versions and the precise intents the user is deciding on. */
@Serializable
data class SyncConfirmation(
    val kind: String,
    val id: Long,
    val deleting: Boolean = false,
    val localPlan: PlanSnapshotEntity? = null,
    val localStrategy: StrategySnapshotEntity? = null,
    val localSchedule: ScheduleSnapshotEntity? = null,
    val remotePlan: SyncPlanView? = null,
    val remoteStrategy: SyncStrategyView? = null,
    val remoteSchedule: SyncScheduleView? = null,
    val intentIds: List<Long> = emptyList(),
    val strategyChanged: Boolean = false,
) {
    val key get() = "$kind:$id"
    val remoteDeleted get() = if (kind == "plan") remotePlan == null else remoteSchedule == null
}

@Serializable
data class BulkDeletion(val plans: List<Long>, val schedules: List<Long>, val approved: Boolean = false) {
    val count get() = plans.size + schedules.size
    fun needsConfirmation(confirmedTotal: Int) = count >= 10 && count.toLong() * 2 > confirmedTotal
}

@Serializable
internal data class DeletedSnapshot(
    val plan: PlanSnapshotEntity? = null,
    val strategy: StrategySnapshotEntity? = null,
    val schedule: ScheduleSnapshotEntity? = null,
)

/** Common identity for edit, strategy and delete intents; telemetry must not block conflict resolution. */
fun PendingRecordEntity.syncEntityKey(): String? = when (type) {
    "plan_upsert", "strategy_upsert", "plan_delete", SnapshotProtection.LOCAL_PLAN_CHANGE -> planId?.let { "plan:$it" }
    "schedule_upsert" -> "schedule:${kotlinx.serialization.json.Json.decodeFromString<SchedulePendingUpsert>(payload.orEmpty()).localId}"
    "schedule_delete" -> "schedule:${requireNotNull(payload?.toLongOrNull())}"
    else -> null
}
