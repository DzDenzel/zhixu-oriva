package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.ScheduleSnapshotEntity

@Dao
interface ScheduleSnapshotDao {
    @Query("UPDATE schedule_snapshot SET linkedPlanId=:newId WHERE scope = :scope AND linkedPlanId=:oldId") suspend fun replaceLinkedPlanId(scope: String, oldId: Long, newId: Long)
    @Upsert suspend fun upsertAll(items: List<ScheduleSnapshotEntity>)
    @Query("SELECT * FROM schedule_snapshot WHERE scope = :scope ORDER BY startDate,startTime,id") suspend fun all(scope: String): List<ScheduleSnapshotEntity>
    @Query("SELECT * FROM schedule_snapshot WHERE scope = :scope AND id=:id") suspend fun byId(scope: String, id: Long): ScheduleSnapshotEntity?
    @Query("DELETE FROM schedule_snapshot WHERE scope = :scope AND id=:id") suspend fun delete(scope: String, id: Long)
    @Query("UPDATE schedule_snapshot SET linkedPlanId=NULL,linkedPlanDeleted=1 WHERE scope = :scope AND linkedPlanId=:planId") suspend fun markPlanDeleted(scope: String, planId: Long)
    @Query("UPDATE schedule_snapshot SET id=:serverId WHERE scope = :scope AND id=:localId") suspend fun replaceId(scope: String, localId: Long,serverId: Long)
}
