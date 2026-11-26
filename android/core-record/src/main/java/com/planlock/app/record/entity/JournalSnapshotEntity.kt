package com.planlock.app.record.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * 日常记录本地快照（Room v12，遵循 dataScope 作用域隔离）。
 */
@Entity(tableName = "journal_snapshot", primaryKeys = ["scope", "id"])
@Serializable
data class JournalSnapshotEntity(
    val id: Long,
    val userId: Long,
    val clientId: String? = null,
    val entryDate: String,
    val mood: String? = null,
    val tagsJson: String? = null,
    val content: String,
    val status: Int = 1,
    val version: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @ColumnInfo(defaultValue = "''") val scope: String = "local\nlocal",
)
