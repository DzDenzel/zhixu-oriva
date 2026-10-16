package com.planlock.app.record.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 执行策略快照缓存。
 */
@Entity(tableName = "strategy_snapshot")
@kotlinx.serialization.Serializable
data class StrategySnapshotEntity(
    @PrimaryKey val id: Long,
    val planId: Long,
    val blacklistApps: String?,
    val whitelistApps: String?,
    val autoDnd: Boolean?,
    val notifySilence: Boolean?,
    val version: Long?,
    // ADR-006：仅本地保存；历史快照 null 按非严格模式执行。
    val strictMode: Boolean? = null
)
