package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.planlock.app.record.entity.StrategySnapshotEntity

@Dao
interface StrategySnapshotDao {

    @Upsert
    suspend fun upsertAll(strategies: List<StrategySnapshotEntity>)

    /** v2 不再读取或保留 blacklist/strictMode；旧列只为兼容 schema 留存。 */
    @Transaction
    suspend fun upsertRemote(strategies: List<StrategySnapshotEntity>) {
        upsertAll(strategies.map { remote ->
            // 首次同步的服务端策略 ID 可能与本地临时 ID 不同，避免同一计划留下两份策略。
            deleteByPlanId(remote.planId)
            remote.copy(blacklistApps = null, strictMode = null)
        })
    }

    @Query("SELECT * FROM strategy_snapshot")
    suspend fun all(): List<StrategySnapshotEntity>

    @Query("DELETE FROM strategy_snapshot")
    suspend fun clear()

    @Query("UPDATE strategy_snapshot SET planId = :newPlanId WHERE planId = :oldPlanId")
    suspend fun replacePlanId(oldPlanId: Long, newPlanId: Long)

    @Query("UPDATE strategy_snapshot SET version = :version WHERE planId = :planId AND COALESCE(version, 0) <= :version")
    suspend fun updateVersion(planId: Long, version: Long)

    @Query("SELECT * FROM strategy_snapshot WHERE planId = :planId")
    suspend fun byPlanId(planId: Long): StrategySnapshotEntity?

    @Query("DELETE FROM strategy_snapshot WHERE planId = :planId")
    suspend fun deleteByPlanId(planId: Long)
}
