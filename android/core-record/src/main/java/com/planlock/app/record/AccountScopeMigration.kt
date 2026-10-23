package com.planlock.app.record

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.*

/** Preserve every legacy row; unknown provenance is retained for explicit design-side review. */
class AccountScopeMigration(private val legacyInstance: () -> String, private val cancelLegacyReminder: (Long) -> Unit = {}) : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val instance = legacyInstance()
        val tables = listOf("plan_snapshot", "schedule_snapshot", "strategy_snapshot", "pending_record", "behavior_aggregate")
        tables.forEach { db.execSQL("ALTER TABLE $it ADD COLUMN scope TEXT NOT NULL DEFAULT ''") }
        db.execSQL("CREATE TABLE IF NOT EXISTS scope_migration_issue (sourceTable TEXT NOT NULL, rowKey TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(sourceTable,rowKey))")
        fun issue(table: String, key: String) = db.execSQL(
            "INSERT OR REPLACE INTO scope_migration_issue VALUES(?,?,?)", arrayOf(table, key, "无法从快照或已保存归属判定；保留待裁决"))
        fun snapshotScopes(table: String): Map<Long, String> = buildMap {
            db.query("SELECT id,userId FROM $table").use { c ->
                while(c.moveToNext()) {
                    val owner = c.getLong(1)
                    val scope = if(owner <= 0) LOCAL else "$instance\n$owner"
                    put(c.getLong(0), scope)
                    db.execSQL("UPDATE $table SET scope=? WHERE id=?", arrayOf(scope, c.getLong(0)))
                }
            }
        }
        val plans = snapshotScopes("plan_snapshot")
        val schedules = snapshotScopes("schedule_snapshot")
        schedules.keys.forEach(cancelLegacyReminder)
        db.query("SELECT id,planId FROM strategy_snapshot").use { c ->
            while(c.moveToNext()) {
                val scope = plans[c.getLong(1)] ?: UNKNOWN.also { issue("strategy_snapshot", c.getLong(0).toString()) }
                db.execSQL("UPDATE strategy_snapshot SET scope=? WHERE id=?", arrayOf(scope,c.getLong(0)))
            }
        }
        fun json(raw: String?): JsonObject? = runCatching { raw?.let { Json.parseToJsonElement(it).jsonObject } }.getOrNull()
        fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.longOrNull
        db.query("SELECT id,type,planId,payload,localSnapshot FROM pending_record").use { c ->
            while(c.moveToNext()) {
                val type = c.getString(1)
                val plan = if(c.isNull(2)) null else c.getLong(2)
                val payload = json(c.getString(3))
                val archived = json(c.getString(4))
                val archivedOwner = listOf("plan","schedule").firstNotNullOfOrNull { key ->
                    (archived?.get(key) as? JsonObject)?.number("userId")
                }
                val kind = payload?.get("kind")?.jsonPrimitive?.contentOrNull
                val scope = when {
                    archivedOwner != null -> if(archivedOwner <= 0) LOCAL else "$instance\n$archivedOwner"
                    type == "schedule_upsert" -> payload?.number("localId")?.let(schedules::get)
                    type == "schedule_delete" -> c.getString(3)?.toLongOrNull()?.let(schedules::get)
                    kind == "schedule" -> plan?.let(schedules::get)
                    type.startsWith("schedule_") -> plan?.let(schedules::get)
                    else -> plan?.let(plans::get)
                } ?: UNKNOWN.also { if(type != "history_seed") issue("pending_record", c.getLong(0).toString()) }
                db.execSQL("UPDATE pending_record SET scope=? WHERE id=?", arrayOf(scope,c.getLong(0)))
            }
        }
        db.query("SELECT planId,packageName,sourceLevel FROM behavior_aggregate").use { c ->
            while(c.moveToNext()) {
                val scope = plans[c.getLong(0)] ?: UNKNOWN.also { issue("behavior_aggregate", "${c.getLong(0)}:${c.getString(1)}:${c.getString(2)}") }
                db.execSQL("UPDATE behavior_aggregate SET scope=? WHERE planId=? AND packageName=? AND sourceLevel=?", arrayOf(scope,c.getLong(0),c.getString(1),c.getString(2)))
            }
        }
        // Old shared-queue seeding could have put account rows in the guest history partition.
        plans.forEach { (id, scope) ->
            db.execSQL("UPDATE OR IGNORE execution_history SET scope=? WHERE planId=? AND scope=?", arrayOf(scope,id,LOCAL))
        }
        tables.filterNot { it == "pending_record" }.forEach { rebuildWithScopedKey(db, it) }
    }

    private fun rebuildWithScopedKey(db: SupportSQLiteDatabase, table: String) {
        data class Column(val name: String, val type: String, val required: Boolean, val default: String?, val key: Int)
        val columns = buildList {
            db.query("PRAGMA table_info($table)").use { c ->
                while(c.moveToNext()) add(Column(c.getString(1), c.getString(2), c.getInt(3) != 0, c.getString(4), c.getInt(5)))
            }
        }
        val definitions = columns.map { "`${it.name}` ${it.type}" + (if(it.required) " NOT NULL" else "") + (it.default?.let { d -> " DEFAULT $d" } ?: "") }
        val keys = listOf("scope") + columns.filter { it.key > 0 }.sortedBy { it.key }.map { it.name }
        db.execSQL("CREATE TABLE ${table}_scoped (${(definitions + "PRIMARY KEY(${keys.joinToString { "`$it`" }})").joinToString()})")
        val names = columns.joinToString { "`${it.name}`" }
        db.execSQL("INSERT INTO ${table}_scoped ($names) SELECT $names FROM $table")
        db.execSQL("DROP TABLE $table")
        db.execSQL("ALTER TABLE ${table}_scoped RENAME TO $table")
    }
    companion object {
        const val LOCAL = "local\nlocal"
        const val UNKNOWN = "unresolved\nlegacy"
    }
}
