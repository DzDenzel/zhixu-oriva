package com.planlock.app.record

import android.content.Context
import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 5,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
    exportSchema = true
)
abstract class PlanLockDatabase : RoomDatabase() {

    abstract fun planSnapshotDao(): PlanSnapshotDao
    abstract fun strategySnapshotDao(): StrategySnapshotDao
    abstract fun pendingRecordDao(): PendingRecordDao
    abstract fun scheduleSnapshotDao(): ScheduleSnapshotDao
    abstract fun behaviorAggregateDao(): BehaviorAggregateDao

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
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
