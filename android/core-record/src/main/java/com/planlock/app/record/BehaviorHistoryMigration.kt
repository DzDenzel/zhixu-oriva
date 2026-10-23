package com.planlock.app.record

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.planlock.app.api.dto.RhythmBehavior
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Adds evidence only. Already-uploaded legacy data is recovered by the new server cursor. */
object BehaviorHistoryMigration : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS behavior_history (scope TEXT NOT NULL, `key` TEXT NOT NULL, planId INTEGER NOT NULL, remoteId INTEGER NOT NULL, payload TEXT NOT NULL, localOrigin INTEGER NOT NULL, PRIMARY KEY(scope,`key`))")
        db.execSQL("ALTER TABLE record_history_cursor ADD COLUMN behaviorId INTEGER NOT NULL DEFAULT 0")
        db.query("SELECT scope,clientId,planId,appName,packageName,startedAt,endedAt,durationMs,recordAt,sourceLevel FROM pending_record WHERE type='violation' AND planId IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val row = RhythmBehavior(clientId = c.getString(1), planId = c.getLong(2), appName = c.getString(3),
                    packageName = c.getString(4), startedAt = c.getString(5), endedAt = c.getString(6),
                    durationMs = if (c.isNull(7)) null else c.getLong(7), recordAt = c.getString(8), sourceLevel = c.getString(9))
                db.execSQL("INSERT OR IGNORE INTO behavior_history VALUES(?,?,?,?,?,?)", arrayOf(c.getString(0),
                    historyKey("violation", row.clientId, 0), row.planId, 0L, Json.encodeToString(row), 1))
            }
        }
    }
}
