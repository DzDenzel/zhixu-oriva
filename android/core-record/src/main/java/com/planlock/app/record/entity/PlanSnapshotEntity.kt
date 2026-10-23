package com.planlock.app.record.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 计划快照缓存（含 version，用于冲突检测与断网执行）。
 */
@Entity(tableName = "plan_snapshot", primaryKeys = ["scope", "id"])
@kotlinx.serialization.Serializable
data class PlanSnapshotEntity(
    val id: Long,
    val userId: Long,
    val name: String,
    val type: String,
    val startTime: String?,
    val endTime: String?,
    val repeatRule: String?,
    val color: String?,
    val icon: String?,
    val remindAdvanceMin: Int?,
    val remindMethods: String?,
    val status: Int?,
    val version: Long?,
    val endedOccurrenceDate: String? = null,
    val statusLog: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''") val scope: String = "local\nlocal",
)
