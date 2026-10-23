package com.planlock.app.record

import android.content.Context
import com.planlock.app.api.planLockPreferences
import kotlinx.coroutines.flow.first
import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.InvalidationTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.planlock.app.record.dao.PendingRecordDao
import com.planlock.app.record.dao.BehaviorAggregateDao
import com.planlock.app.record.dao.PlanSnapshotDao
import com.planlock.app.record.dao.StrategySnapshotDao
import com.planlock.app.record.dao.ScheduleSnapshotDao
import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.BehaviorAggregateEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity

/**
 * 本地 Room 数据库：计划/策略快照 + 补传队列。
 */
@Database(
    entities = [
        PlanSnapshotEntity::class,
        StrategySnapshotEntity::class,
        PendingRecordEntity::class,
        ScheduleSnapshotEntity::class,
        BehaviorAggregateEntity::class,
        com.planlock.app.record.entity.ExecutionHistoryEntity::class,
        com.planlock.app.record.entity.RecordHistoryCursor::class,
        com.planlock.app.record.entity.ScopeMigrationIssue::class,
        com.planlock.app.record.entity.PlanTypeSnapshotEntity::class,
        com.planlock.app.record.entity.BehaviorHistoryEntity::class,
    ],
    version = 11,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
    exportSchema = true
)
abstract class PlanLockDatabase : RoomDatabase() {

    /** One signal per committed invalidation batch, shared by every RecordStore of this database. */
    val localRevision: StateFlow<Long> by lazy {
        val revision = MutableStateFlow(0L)
        invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "plan_snapshot", "strategy_snapshot", "schedule_snapshot", "pending_record", "behavior_aggregate",
            "execution_history", "record_history_cursor", "plan_type_snapshot", "behavior_history",
        ) {
            override fun onInvalidated(tables: Set<String>) {
                revision.update { it + 1 }
            }
        })
        revision.asStateFlow()
    }

    abstract fun planSnapshotDao(): PlanSnapshotDao
    abstract fun strategySnapshotDao(): StrategySnapshotDao
    abstract fun pendingRecordDao(): PendingRecordDao
    abstract fun scheduleSnapshotDao(): ScheduleSnapshotDao
    abstract fun behaviorAggregateDao(): BehaviorAggregateDao
    abstract fun executionHistoryDao(): com.planlock.app.record.dao.ExecutionHistoryDao
    abstract fun planTypeSnapshotDao(): com.planlock.app.record.dao.PlanTypeSnapshotDao
    abstract fun behaviorHistoryDao(): com.planlock.app.record.dao.BehaviorHistoryDao

    companion object {
        // 保留既有计划、策略和待上传记录，仅追加可空的本地列。
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE strategy_snapshot ADD COLUMN strictMode INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: PlanLockDatabase? = null

        fun get(context: Context): PlanLockDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlanLockDatabase::class.java,
                    "planlock.db"
                ).addMigrations(MIGRATION_1_2, PlanTypeMigration, BehaviorHistoryMigration, ScheduleLocationMigration, AccountScopeMigration(legacyInstance = {
                    val preferences = kotlinx.coroutines.runBlocking { context.planLockPreferences.data.first() }
                    preferences[com.planlock.app.api.TokenStore.legacyOwnerInstanceKey]
                        ?: preferences[androidx.datastore.preferences.core.stringPreferencesKey("auth_data_owner_instance")]
                        ?: com.planlock.app.api.TokenStore.state.value.dataOwnerInstance
                        ?: com.planlock.app.api.ConnectionConfig.legacyInstanceId
                }, cancelLegacyReminder = { ScheduleReminderScheduler(context).cancelLegacy(it) })).build().also { INSTANCE = it }
            }
    }
}
