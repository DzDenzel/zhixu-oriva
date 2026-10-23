package com.planlock.app.record.entity

import androidx.room.Entity

/** Durable history is separate from the acknowledged/deleted upload queue. */
@Entity(tableName = "execution_history", primaryKeys = ["scope", "key"])
data class ExecutionHistoryEntity(
    val scope: String,
    val key: String,
    val type: String,
    val serverId: Long = 0,
    val planId: Long,
    val planDate: String? = null,
    val status: Int? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val unlockAt: String? = null,
    val method: String? = null,
    val clientId: String? = null,
    val deviceId: Long = 0,
    val localOrigin: Boolean = false,
)

@Entity(tableName = "record_history_cursor", primaryKeys = ["scope"])
data class RecordHistoryCursor(val scope: String, val executionId: Long = 0, val unlockId: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val behaviorId: Long = 0)
