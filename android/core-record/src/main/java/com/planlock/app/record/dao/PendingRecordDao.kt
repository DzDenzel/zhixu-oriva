package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.planlock.app.record.entity.PendingRecordEntity

@Dao
interface PendingRecordDao {

    @Update
    suspend fun update(record: PendingRecordEntity)

    @Insert
    suspend fun insert(record: PendingRecordEntity)

    @Query("SELECT COUNT(*) FROM pending_record WHERE clientId = :clientId")
    suspend fun countByClientId(clientId: String): Int

    @Query("SELECT * FROM pending_record ORDER BY id ASC")
    suspend fun all(): List<PendingRecordEntity>

    @Query("SELECT * FROM pending_record WHERE clientId = :clientId LIMIT 1")
    suspend fun byClientId(clientId: String): PendingRecordEntity?

    @Query("SELECT * FROM pending_record WHERE id = :id")
    suspend fun byId(id: Long): PendingRecordEntity?

    @Query("DELETE FROM pending_record WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM pending_record WHERE clientId = :clientId")
    suspend fun deleteByClientId(clientId: String)

    @Query("UPDATE pending_record SET planId = :newId WHERE planId = :oldId")
    suspend fun replaceProtectedPlanId(oldId: Long, newId: Long)

    @Query("SELECT * FROM pending_record WHERE type = 'violation' AND startedAt IS NOT NULL AND endedAt IS NOT NULL AND durationMs IS NOT NULL ORDER BY createdAt ASC, id ASC")
    suspend fun behaviorDetails(): List<PendingRecordEntity>
}
