package com.planlock.app.record.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.planlock.app.record.entity.JournalSnapshotEntity

@Dao
interface JournalSnapshotDao {
    @Upsert
    suspend fun upsertAll(items: List<JournalSnapshotEntity>)

    @Query("SELECT * FROM journal_snapshot WHERE scope = :scope AND status = 1 ORDER BY entryDate DESC, id DESC")
    suspend fun active(scope: String): List<JournalSnapshotEntity>

    @Query("SELECT * FROM journal_snapshot WHERE scope = :scope ORDER BY entryDate DESC, id DESC")
    suspend fun all(scope: String): List<JournalSnapshotEntity>

    @Query("SELECT * FROM journal_snapshot WHERE scope = :scope AND id = :id")
    suspend fun byId(scope: String, id: Long): JournalSnapshotEntity?

    @Query("DELETE FROM journal_snapshot WHERE scope = :scope AND id = :id")
    suspend fun delete(scope: String, id: Long)

    @Query("UPDATE journal_snapshot SET id = :serverId WHERE scope = :scope AND id = :localId")
    suspend fun replaceId(scope: String, localId: Long, serverId: Long)
}
