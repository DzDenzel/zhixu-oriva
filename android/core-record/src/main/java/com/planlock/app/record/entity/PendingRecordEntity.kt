package com.planlock.app.record.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 离线补传队列：执行/违规/解锁记录（client_id 幂等，断网积压、恢复补传）。
 */
@Entity(tableName = "pending_record")
data class PendingRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val planId: Long?,
    val planDate: String? = null,
    val status: Int? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val recordAt: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val durationMs: Long? = null,
    val sourceLevel: String? = null,
    val unlockAt: String? = null,
    val method: String? = null,
    val clientId: String,
    val payload: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val baseVersion: Long? = null,
    val localSnapshot: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''") val scope: String = "local\nlocal",
)
