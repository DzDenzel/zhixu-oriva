package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.BehaviorAggregateEntity

@Dao
interface BehaviorAggregateDao {
    @Upsert suspend fun upsertAll(items: List<BehaviorAggregateEntity>)
    @Query("DELETE FROM behavior_aggregate WHERE planId=:id") suspend fun deleteByPlanId(id: Long)

    @Query(
        """INSERT INTO behavior_aggregate(planId, packageName, sourceLevel, totalDurationMs, occurrenceCount, lastEndedAt)
        VALUES(:planId, :packageName, :sourceLevel, :durationMs, 1, :endedAt)
        ON CONFLICT(planId, packageName, sourceLevel) DO UPDATE SET
          totalDurationMs = totalDurationMs + :durationMs,
          occurrenceCount = occurrenceCount + 1,
          lastEndedAt = :endedAt"""
    )
    suspend fun add(
        planId: Long,
        packageName: String,
        sourceLevel: String,
        durationMs: Long,
        endedAt: String?,
    )

    @Query("SELECT * FROM behavior_aggregate ORDER BY totalDurationMs DESC")
    suspend fun all(): List<BehaviorAggregateEntity>
}
