package com.planlock.app.record.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 日程本地快照。与计划独立，不被执行引擎读取。 */
@Entity(tableName = "schedule_snapshot")
@kotlinx.serialization.Serializable
data class ScheduleSnapshotEntity(
    @PrimaryKey val id: Long,
    val userId: Long,
    val title: String,
    val startDate: String,
    val startTime: String?,
    val endDate: String,
    val endTime: String?,
    val allDay: Boolean,
    val location: String?,
    val note: String?,
    val remindAdvanceMin: Int?,
    val repeatRule: String?,
    val linkedPlanId: Long?,
    val linkedPlanDeleted: Boolean = false,
    val tzId: String?,
    val status: Int,
    val version: Long,
)
