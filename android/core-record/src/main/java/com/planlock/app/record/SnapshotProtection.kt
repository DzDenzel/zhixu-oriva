package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Derive the protected IDs inside the same transaction that commits remote snapshots. */
data class SnapshotProtection(val plans: Set<Long>, val schedules: Set<Long>) {
    companion object {
        const val LOCAL_PLAN_CHANGE = "local_plan_change"
        fun from(pending: List<PendingRecordEntity>): SnapshotProtection = SnapshotProtection(
            pending.filter { it.type in setOf(LOCAL_PLAN_CHANGE, "plan_upsert", "strategy_upsert", "plan_delete") }.mapNotNull { it.planId }.toSet(),
            pending.mapNotNull {
                when (it.type) {
                    "schedule_upsert" -> Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).localId
                    "schedule_delete" -> requireNotNull(it.payload?.toLongOrNull())
                    else -> null
                }
            }.toSet(),
        )
    }
}
