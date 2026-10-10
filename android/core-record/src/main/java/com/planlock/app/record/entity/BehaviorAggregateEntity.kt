package com.planlock.app.record.entity

import androidx.room.Entity

/** 永久保留的行为时长聚合；明细清理不影响此表。 */
@Entity(
    tableName = "behavior_aggregate",
    primaryKeys = ["planId", "packageName", "sourceLevel"],
)
data class BehaviorAggregateEntity(
    val planId: Long,
    val packageName: String,
    val sourceLevel: String,
    val totalDurationMs: Long,
    val occurrenceCount: Long,
    val lastEndedAt: String?,
)
