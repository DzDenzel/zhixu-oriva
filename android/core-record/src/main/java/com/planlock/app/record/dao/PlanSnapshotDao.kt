package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.PlanSnapshotEntity

@Dao
interface PlanSnapshotDao {

    @Upsert
    suspend fun upsertAll(plans: List<PlanSnapshotEntity>)

    @Query("SELECT * FROM plan_snapshot")
    suspend fun all(): List<PlanSnapshotEntity>

    @Query("DELETE FROM plan_snapshot")
    suspend fun clear()

    @Query("UPDATE plan_snapshot SET id = :newId WHERE id = :oldId")
    suspend fun replaceId(oldId: Long, newId: Long)

    @Query("UPDATE plan_snapshot SET version = :version WHERE id = :id AND COALESCE(version, 0) <= :version")
    suspend fun updateVersion(id: Long, version: Long)

    @Query("SELECT * FROM plan_snapshot WHERE id = :id")
    suspend fun byId(id: Long): PlanSnapshotEntity?

    @Query("DELETE FROM plan_snapshot WHERE id = :id")
    suspend fun delete(id: Long)
}
