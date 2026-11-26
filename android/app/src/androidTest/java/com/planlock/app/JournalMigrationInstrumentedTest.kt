package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.SyncItemResult
import com.planlock.app.api.dto.SyncJournalView
import com.planlock.app.api.dto.SyncPullResponse
import com.planlock.app.api.dto.SyncPushRequest
import com.planlock.app.api.dto.SyncPushResponse
import com.planlock.app.api.mock.MockApi
import com.planlock.app.record.AccountScopeMigration
import com.planlock.app.record.BehaviorHistoryMigration
import com.planlock.app.record.JournalMigration
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.PlanTypeMigration
import com.planlock.app.record.RecordStore
import com.planlock.app.record.ScheduleLocationMigration
import com.planlock.app.sync.SyncClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * REQ-039 A5: 验证 Room v11 -> v12 零数据丢失迁移、dataScope 隔离、localRevision 触发及负 ID 重绑。
 */
class JournalMigrationInstrumentedTest {

    @Test
    fun migration11To12RetainsExistingTablesAndSupportsScopedJournalSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal-migration-${UUID.randomUUID()}.db"
        val legacy = context.openOrCreateDatabase(name, 0, null)
        try {
            val schema = JSONObject(
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("com.planlock.app.record.PlanLockDatabase/11.json")
                    .bufferedReader().readText(),
            ).getJSONObject("database")
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            legacy.execSQL(
                "INSERT INTO plan_snapshot (scope,id,userId,name,type,startTime,endTime,remindAdvanceMin,status,version) " +
                    "VALUES ('instance\n1',10,1,'晨读','学习','07:00','07:30',15,1,2)",
            )
            legacy.execSQL(
                "INSERT INTO schedule_snapshot (scope,id,userId,title,startDate,endDate,allDay,linkedPlanDeleted,status,version) " +
                    "VALUES ('instance\n1',20,1,'体检','2026-11-26','2026-11-26',1,0,1,1)",
            )
            legacy.version = 11
        } finally {
            legacy.close()
        }

        val db = Room.databaseBuilder(context, PlanLockDatabase::class.java, name)
            .addMigrations(
                AccountScopeMigration({ "legacy" }),
                PlanTypeMigration,
                BehaviorHistoryMigration,
                ScheduleLocationMigration,
                JournalMigration,
            )
            .build()
        try {
            assertEquals("晨读", db.planSnapshotDao().all("instance\n1").single().name)
            assertEquals("体检", db.scheduleSnapshotDao().all("instance\n1").single().title)

            var activeScope = "instance\n1"
            val store = RecordStore(db, { activeScope })
            val beforeRevision = store.localRevision.value
            val localId = store.saveJournalChange(
                entryDate = "2026-11-26",
                mood = "平静",
                tags = listOf("阅读", "晨间"),
                content = "迁移后第一条日记",
            )
            assertTrue(localId < 0)
            withTimeout(5_000) { store.localRevision.first { it > beforeRevision } }

            // 作用域隔离：切换到账号 2 或游客看不到账号 1 的日记与待上传队列
            activeScope = "instance\n2"
            assertTrue(store.journals().isEmpty())
            assertTrue(store.pending().isEmpty())
            activeScope = "local\nlocal"
            assertTrue(store.journals().isEmpty())

            // 切回账号 1，同步推送并验证负 ID 到远端 ID 重绑
            activeScope = "instance\n1"
            val remoteStore = mutableListOf<SyncJournalView>()
            val api = object : PlanLockApi by MockApi() {
                override suspend fun syncPush(req: SyncPushRequest): ApiResponse<SyncPushResponse> {
                    val sent = req.journals!!.single()
                    val savedId = sent.id ?: 901L
                    val nextVersion = if (sent.id == null) 0L else (sent.baseVersion ?: 0L) + 1L
                    remoteStore.removeAll { it.id == savedId }
                    remoteStore += SyncJournalView(
                        id = savedId,
                        userId = 1L,
                        clientId = sent.clientId,
                        entryDate = sent.entryDate,
                        mood = sent.mood,
                        tags = sent.tags,
                        content = sent.content,
                        status = sent.status,
                        version = nextVersion,
                    )
                    return ApiResponse(
                        data = SyncPushResponse(
                            listOf(
                                SyncItemResult(
                                    key = "journal:${sent.id}",
                                    ok = true,
                                    id = savedId,
                                    version = nextVersion,
                                    clientId = sent.clientId,
                                )
                            )
                        )
                    )
                }

                override suspend fun syncPull() = ApiResponse(
                    data = SyncPullResponse(
                        plans = emptyList(),
                        schedules = emptyList(),
                        strategies = emptyList(),
                        journals = remoteStore.toList(),
                    )
                )
            }
            val client = SyncClient(api, store, owner = { 1L })
            client.pushPendingRecords()
            assertTrue(store.pending().isEmpty())
            assertEquals(901L, store.journals().single().id)
            assertEquals(901L, store.resolveJournalId(localId))

            // 软删同步
            store.softDeleteJournal(localId)
            assertTrue(store.journals().isEmpty())
            client.pushPendingRecords()
            assertEquals(0, remoteStore.single().status)
            assertEquals(1L, remoteStore.single().version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
