package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.planlock.app.record.entity.PendingRecordEntity

@Dao
interface PendingRecordDao {

    @Query("SELECT * FROM pending_record WHERE scope = :scope AND type IN ('sync_conflict', 'bulk_delete') ORDER BY id")
    fun observeConfirmations(scope: String): kotlinx.coroutines.flow.Flow<List<PendingRecordEntity>>

    @Update
    suspend fun update(record: PendingRecordEntity)

    @Insert
    suspend fun insert(record: PendingRecordEntity)

    @Query("SELECT COUNT(*) FROM pending_record WHERE scope = :scope AND clientId = :clientId")
    suspend fun countByClientId(scope: String, clientId: String): Int

    @Query("SELECT * FROM pending_record WHERE scope = :scope ORDER BY id ASC")
    suspend fun all(scope: String): List<PendingRecordEntity>

    @Query("SELECT * FROM pending_record WHERE scope = :scope AND clientId = :clientId LIMIT 1")
    suspend fun byClientId(scope: String, clientId: String): PendingRecordEntity?

    @Query("SELECT * FROM pending_record WHERE scope = :scope AND id = :id")
    suspend fun byId(scope: String, id: Long): PendingRecordEntity?

    @Query("DELETE FROM pending_record WHERE scope = :scope AND id IN (:ids)")
    suspend fun delete(scope: String, ids: List<Long>)

    @Query("DELETE FROM pending_record WHERE scope = :scope AND clientId = :clientId")
    suspend fun deleteByClientId(scope: String, clientId: String)

    @Query("UPDATE pending_record SET planId = :newId WHERE scope = :scope AND planId = :oldId")
    suspend fun replaceProtectedPlanId(scope: String, oldId: Long, newId: Long)

    @Query("SELECT * FROM pending_record WHERE scope = :scope AND type = 'violation' AND startedAt IS NOT NULL AND endedAt IS NOT NULL AND durationMs IS NOT NULL ORDER BY createdAt ASC, id ASC")
    suspend fun behaviorDetails(scope: String): List<PendingRecordEntity>
}
