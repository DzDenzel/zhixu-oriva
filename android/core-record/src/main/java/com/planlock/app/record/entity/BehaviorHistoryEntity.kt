package com.planlock.app.record.entity

import androidx.room.Entity

/** Durable account-scoped evidence; upload acknowledgement must never delete this row. */
@Entity(tableName = "behavior_history", primaryKeys = ["scope", "key"])
data class BehaviorHistoryEntity(
    val scope: String,
    val key: String,
    val planId: Long,
    val remoteId: Long,
    val payload: String,
    val localOrigin: Boolean,
)
