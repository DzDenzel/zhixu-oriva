package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.planlock.app.record.AccountScopeMigration

class AccountScopeInstrumentedTest {
    @Test fun mixedLegacyCopyMigratesWithoutDeletingAnyRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath("scope-migration-test.db")
        context.deleteDatabase(path.name)
        path.parentFile!!.mkdirs()
        java.io.File("/data/local/tmp/bug108-mixed.db").copyTo(path, overwrite = true)
        val db = Room.databaseBuilder(context, PlanLockDatabase::class.java, path.name)
            .addMigrations(AccountScopeMigration({ "legacy" }), com.planlock.app.record.PlanTypeMigration, com.planlock.app.record.BehaviorHistoryMigration, com.planlock.app.record.ScheduleLocationMigration).build()
        try {
            assertEquals(2, db.planSnapshotDao().all("local\nlocal").size)
            assertEquals(2, db.planSnapshotDao().all("legacy\n7").size)
            assertEquals(1, db.scheduleSnapshotDao().all("legacy\n1").size)
            assertEquals(6, db.scheduleSnapshotDao().all("legacy\n7").size)
            val sql = db.openHelper.readableDatabase
            for ((table, expected) in mapOf("plan_snapshot" to 4, "schedule_snapshot" to 7, "pending_record" to 44, "strategy_snapshot" to 3, "behavior_aggregate" to 5, "execution_history" to 15)) {
                sql.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertEquals(table, expected, c.getInt(0)) }
            }
            val issues = buildList {
                sql.query("SELECT sourceTable,rowKey,reason FROM scope_migration_issue ORDER BY sourceTable,rowKey").use { c ->
                    while(c.moveToNext()) add("${c.getString(0)} | ${c.getString(1)} | ${c.getString(2)}")
                }
            }
            context.filesDir.resolve("scope-migration-issues.txt").writeText(issues.joinToString("\n"), Charsets.UTF_8)
        } finally { db.close(); context.deleteDatabase(path.name) }
    }
    @Test fun switchAccountsRetainsSnapshotsAndOutboxWithoutLeaking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            var scope = "instance\n1"
            val store = RecordStore(db, { scope })
            val first = PlanSnapshotEntity(1, 1, "A", "study", "09:00", "10:00", null, null, null, 0, null, 1, 0)
            store.updateLocalPlan(first)
            assertEquals(listOf("A"), store.plans().map { it.name })
            scope = "instance\n2"
            assertTrue(store.plans().isEmpty())
            assertTrue(store.pending().isEmpty())
            store.updateLocalPlan(first.copy(userId = 2, name = "B"))
            assertEquals(listOf("B"), store.plans().map { it.name })
            scope = "local\nlocal"
            assertTrue(store.plans().isEmpty())
            assertTrue(store.pending().isEmpty())
            scope = "instance\n1"
            assertEquals(listOf("A"), store.plans().map { it.name })
            assertEquals(1, store.pending().size)
        } finally { db.close() }
    }
}
