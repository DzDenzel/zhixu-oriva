package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.PlanTypeSnapshotEntity

@Dao
interface PlanTypeSnapshotDao {
    @Query("SELECT * FROM plan_type_snapshot WHERE scope=:scope ORDER BY typeKey")
    suspend fun all(scope: String): List<PlanTypeSnapshotEntity>
    @Upsert suspend fun upsert(rows: List<PlanTypeSnapshotEntity>)
    @Query("DELETE FROM plan_type_snapshot WHERE scope=:scope") suspend fun clear(scope: String)
}
