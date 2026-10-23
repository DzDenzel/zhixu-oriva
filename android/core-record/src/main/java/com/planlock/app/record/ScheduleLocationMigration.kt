package com.planlock.app.record

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ScheduleLocationMigration : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedule_snapshot ADD COLUMN locationLat REAL")
        db.execSQL("ALTER TABLE schedule_snapshot ADD COLUMN locationLon REAL")
    }
}
