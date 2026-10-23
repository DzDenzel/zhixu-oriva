package com.planlock.app.record

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** No plan value is rewritten by this migration. */
object PlanTypeMigration : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS plan_type_snapshot (scope TEXT NOT NULL, typeKey TEXT NOT NULL, name TEXT NOT NULL, color TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(scope,typeKey))")
    }
}
