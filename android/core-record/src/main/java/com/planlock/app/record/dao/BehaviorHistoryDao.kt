package com.planlock.app.record.dao

import androidx.room.*
import com.planlock.app.record.entity.BehaviorHistoryEntity

@Dao
interface BehaviorHistoryDao {
    @Query("SELECT * FROM behavior_history WHERE scope = :scope")
    suspend fun all(scope: String): List<BehaviorHistoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<BehaviorHistoryEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: BehaviorHistoryEntity)
    @Query("DELETE FROM behavior_history WHERE scope = :scope")
    suspend fun deleteScope(scope: String)
}
