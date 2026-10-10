package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.ScheduleSnapshotEntity

@Dao
interface ScheduleSnapshotDao {
    @Query("UPDATE schedule_snapshot SET linkedPlanId=:newId WHERE linkedPlanId=:oldId") suspend fun replaceLinkedPlanId(oldId: Long, newId: Long)
    @Upsert suspend fun upsertAll(items: List<ScheduleSnapshotEntity>)
    @Query("SELECT * FROM schedule_snapshot ORDER BY startDate,startTime,id") suspend fun all(): List<ScheduleSnapshotEntity>
    @Query("SELECT * FROM schedule_snapshot WHERE id=:id") suspend fun byId(id: Long): ScheduleSnapshotEntity?
    @Query("DELETE FROM schedule_snapshot WHERE id=:id") suspend fun delete(id: Long)
    @Query("UPDATE schedule_snapshot SET linkedPlanId=NULL,linkedPlanDeleted=1 WHERE linkedPlanId=:planId") suspend fun markPlanDeleted(planId: Long)
    @Query("UPDATE schedule_snapshot SET id=:serverId WHERE id=:localId") suspend fun replaceId(localId: Long,serverId: Long)
}
