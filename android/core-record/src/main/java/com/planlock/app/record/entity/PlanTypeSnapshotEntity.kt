package com.planlock.app.record.entity

import androidx.room.Entity

@Entity(tableName = "plan_type_snapshot", primaryKeys = ["scope", "typeKey"])
data class PlanTypeSnapshotEntity(
    val scope: String,
    val typeKey: String,
    val name: String,
    val color: String,
    val version: Long = 0,
    val deleted: Boolean = false,
)
