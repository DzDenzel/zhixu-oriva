package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.PlanSnapshotEntity

@Dao
interface PlanSnapshotDao {

    @Upsert
    suspend fun upsertAll(plans: List<PlanSnapshotEntity>)

    @Query("SELECT * FROM plan_snapshot WHERE scope = :scope")
    suspend fun all(scope: String): List<PlanSnapshotEntity>

    @Query("DELETE FROM plan_snapshot WHERE scope = :scope")
    suspend fun clear(scope: String)

    @Query("UPDATE plan_snapshot SET id = :newId WHERE scope = :scope AND id = :oldId")
    suspend fun replaceId(scope: String, oldId: Long, newId: Long)

    @Query("UPDATE plan_snapshot SET version = :version WHERE scope = :scope AND id = :id AND COALESCE(version, 0) <= :version")
    suspend fun updateVersion(scope: String, id: Long, version: Long)

    @Query("SELECT * FROM plan_snapshot WHERE scope = :scope AND id = :id")
    suspend fun byId(scope: String, id: Long): PlanSnapshotEntity?

    @Query("DELETE FROM plan_snapshot WHERE scope = :scope AND id = :id")
    suspend fun delete(scope: String, id: Long)
}
