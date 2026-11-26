package com.planlock.app.record

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room v11 -> v12 迁移：新增 journal_snapshot 表，保留既有 10 张表零损失。
 */
object JournalMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `journal_snapshot` (
                `id` INTEGER NOT NULL,
                `userId` INTEGER NOT NULL,
                `clientId` TEXT,
                `entryDate` TEXT NOT NULL,
                `mood` TEXT,
                `tagsJson` TEXT,
                `content` TEXT NOT NULL,
                `status` INTEGER NOT NULL,
                `version` INTEGER NOT NULL,
                `createdAt` TEXT,
                `updatedAt` TEXT,
                `scope` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`scope`, `id`)
            )
            """.trimIndent()
        )
    }
}
