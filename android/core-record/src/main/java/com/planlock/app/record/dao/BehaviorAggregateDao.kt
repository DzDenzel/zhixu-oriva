package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.BehaviorAggregateEntity

@Dao
interface BehaviorAggregateDao {
    @Upsert suspend fun upsertAll(items: List<BehaviorAggregateEntity>)
    @Query("DELETE FROM behavior_aggregate WHERE scope = :scope AND planId=:id") suspend fun deleteByPlanId(scope: String, id: Long)

    @Query(
        """INSERT INTO behavior_aggregate(scope, planId, packageName, sourceLevel, totalDurationMs, occurrenceCount, lastEndedAt)
        VALUES(:scope, :planId, :packageName, :sourceLevel, :durationMs, 1, :endedAt)
        ON CONFLICT(scope, planId, packageName, sourceLevel) DO UPDATE SET
          totalDurationMs = totalDurationMs + :durationMs,
          occurrenceCount = occurrenceCount + 1,
          lastEndedAt = :endedAt"""
    )
    suspend fun add(scope: String,
        planId: Long,
        packageName: String,
        sourceLevel: String,
        durationMs: Long,
        endedAt: String?,
    )

    @Query("SELECT * FROM behavior_aggregate WHERE scope = :scope ORDER BY totalDurationMs DESC")
    suspend fun all(scope: String): List<BehaviorAggregateEntity>
}
