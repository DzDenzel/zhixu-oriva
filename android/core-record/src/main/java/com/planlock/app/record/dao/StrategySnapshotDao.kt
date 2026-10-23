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
            deleteByPlanId(remote.scope, remote.planId)
            remote.copy(blacklistApps = null, strictMode = null)
        })
    }

    @Query("SELECT * FROM strategy_snapshot WHERE scope = :scope")
    suspend fun all(scope: String): List<StrategySnapshotEntity>

    @Query("DELETE FROM strategy_snapshot WHERE scope = :scope")
    suspend fun clear(scope: String)

    @Query("UPDATE strategy_snapshot SET planId = :newPlanId WHERE scope = :scope AND planId = :oldPlanId")
    suspend fun replacePlanId(scope: String, oldPlanId: Long, newPlanId: Long)

    @Query("UPDATE strategy_snapshot SET version = :version WHERE scope = :scope AND planId = :planId AND COALESCE(version, 0) <= :version")
    suspend fun updateVersion(scope: String, planId: Long, version: Long)

    @Query("SELECT * FROM strategy_snapshot WHERE scope = :scope AND planId = :planId")
    suspend fun byPlanId(scope: String, planId: Long): StrategySnapshotEntity?

    @Query("DELETE FROM strategy_snapshot WHERE scope = :scope AND planId = :planId")
    suspend fun deleteByPlanId(scope: String, planId: Long)
}
