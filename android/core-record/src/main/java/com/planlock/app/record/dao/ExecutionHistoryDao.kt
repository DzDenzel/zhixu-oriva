package com.planlock.app.record.dao

import androidx.room.*
import com.planlock.app.record.entity.ExecutionHistoryEntity
import com.planlock.app.record.entity.RecordHistoryCursor

@Dao
interface ExecutionHistoryDao {
    @Query("SELECT * FROM execution_history WHERE scope = :scope")
    suspend fun all(scope: String): List<ExecutionHistoryEntity>
    @Query("SELECT * FROM execution_history WHERE scope = :scope AND type = 'execution' AND planDate BETWEEN :fromDate AND :throughDate")
    suspend fun forDates(scope: String, fromDate: String, throughDate: String): List<ExecutionHistoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ExecutionHistoryEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ExecutionHistoryEntity)
    @Query("UPDATE execution_history SET planId = :newId WHERE planId = :oldId AND scope = :scope")
    suspend fun mapPlan(scope: String, oldId: Long, newId: Long)
    @Query("DELETE FROM execution_history WHERE scope = :scope")
    suspend fun deleteScope(scope: String)
    @Query("SELECT * FROM record_history_cursor WHERE scope = :scope")
    suspend fun cursor(scope: String): RecordHistoryCursor?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(value: RecordHistoryCursor)
}
