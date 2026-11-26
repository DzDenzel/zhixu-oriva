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
import com.planlock.app.record.SyncConfirmation
import com.planlock.app.record.entity.JournalSnapshotEntity
import com.planlock.app.sync.SyncClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

            // A7-06 并发竞态回归：离线新建后、在途上传未 ack 前本地删除，ack 到达后必须补发软删，拉取不得复活
            val raceLocalId = store.saveJournalChange(
                entryDate = "2026-11-26",
                mood = "专注",
                tags = listOf("竞态测试"),
                content = "在途创建后立即删除",
            )
            val inFlightRow = store.pending().single()
            store.softDeleteJournal(raceLocalId)
            assertTrue(store.journals().isEmpty())

            // 模拟先前已在途的 status=1 创建请求在服务端成功落库并返回 serverId=902
            val serverId = 902L
            remoteStore += SyncJournalView(
                id = serverId,
                userId = 1L,
                clientId = store.journal(raceLocalId)?.clientId,
                entryDate = "2026-11-26",
                mood = "专注",
                tags = listOf("竞态测试"),
                content = "在途创建后立即删除",
                status = 1,
                version = 0L,
            )
            store.acknowledgeJournal(inFlightRow, serverId, 0L)
            assertTrue(store.pending().isNotEmpty())
            client.pushPendingRecords()
            assertTrue(store.pending().isEmpty())
            client.pull()
            assertTrue(store.journals().isEmpty())
            assertEquals(0, remoteStore.first { it.id == serverId }.status)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun withDb(scopeProvider: () -> String, block: suspend (PlanLockDatabase, RecordStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            block(db, RecordStore(db, scopeProvider))
        } finally {
            db.close()
        }
    }

    @Test
    fun unsentDraftPurgesWithZeroUploadAndLostCreateReceiptAutoFollowsDelete() = runBlocking {
        var activeScope = "instance\n1"
        withDb({ activeScope }) { db, store ->
            val api = MockApi()
            val client = SyncClient(api, store, owner = { 1L })

            // 1. Draft never sent over network -> deleted locally -> zero upload of private content
            val unsentId = store.saveJournalChange("2026-11-26", "平静", listOf("私密"), "绝不能外传的草稿")
            store.softDeleteJournal(unsentId)
            assertTrue(store.journals().isEmpty())
            assertTrue(db.journalSnapshotDao().all(activeScope).isEmpty())
            assertTrue(store.pending().isEmpty())
            client.pushPendingRecords()
            assertTrue(api.journals().data!!.isEmpty())

            // 2. First status=1 create hit server (persisted status=1), response dropped after prepareJournalSend,
            //    user deletes offline (status=0, id=null), next push sees status=1 and follows up with status=0!
            val localId = store.saveJournalChange("2026-11-26", "专注", listOf("丢回执"), "丢首包回执测试正文")
            val createdSnapshot = store.journal(localId)!!
            val createdRow = store.pending().single()
            assertNotNull(store.prepareJournalSend(createdRow.id))
            // Simulate initial status=1 create persisted on server while receipt was lost:
            val serverFirst = api.syncPush(
                SyncPushRequest(
                    journals = listOf(
                        com.planlock.app.api.dto.SyncJournalItem(
                            id = null,
                            clientId = createdSnapshot.clientId,
                            entryDate = createdSnapshot.entryDate,
                            mood = createdSnapshot.mood,
                            tags = listOf("丢回执"),
                            content = createdSnapshot.content,
                            status = 1,
                        )
                    )
                )
            ).data!!.results!!.single()
            assertEquals(1, serverFirst.status)

            // User soft-deletes before ack arrived -> must keep durable status=0 outbox
            store.softDeleteJournal(localId)
            assertTrue(store.journals().isEmpty())
            assertEquals(1, store.pending().size)

            // Multi-round drain pushes status=0 create -> receives remoteStatus=1 -> enqueues (id=serverId, status=0) -> completes delete!
            client.pushPendingRecords()
            assertTrue(store.pending().isEmpty())
            assertTrue(api.journals().data!!.isEmpty())
            val remoteAfter = api.syncPull().data!!.journals!!.first { it.id == serverFirst.id }
            assertEquals(0, remoteAfter.status)
            assertEquals(1L, remoteAfter.version)

            // 3. Old server protocol omitting `journals` (null) must NOT clear local soft-delete tombstone or pending delete
            val id2 = store.saveJournalChange("2026-11-26", "开心", emptyList(), "旧端兼容性")
            client.pushPendingRecords()
            val canonicalId2 = store.resolveJournalId(id2)
            store.softDeleteJournal(canonicalId2)
            val legacyApi = object : PlanLockApi by MockApi() {
                override suspend fun syncPull() = ApiResponse(
                    data = SyncPullResponse(plans = emptyList(), schedules = emptyList(), strategies = emptyList(), journals = null)
                )
            }
            SyncClient(legacyApi, store, owner = { 1L }).pull()
            assertEquals(1, store.pending().size)
            assertEquals(0, db.journalSnapshotDao().byId(activeScope, canonicalId2)?.status)
        }
    }

    @Test
    fun conflictConfirmationAndGuestMergeRespectScopeAndPreserveDivergentContent() = runBlocking {
        var activeScope = "instance\n1"
        withDb({ activeScope }) { db, store ->
            // Create 1409 conflict in instance\n1
            val localId = store.saveJournalChange("2026-11-26", "平静", listOf("同步"), "本机编辑")
            val row1 = store.pending().single()
            store.acknowledgeJournal(row1, 501L, 0L, 1)
            store.saveJournalChange("2026-11-26", "平静", listOf("同步"), "本机二改", id = 501L)
            val conflictPull = SyncPullResponse(
                plans = emptyList(),
                schedules = emptyList(),
                strategies = emptyList(),
                journals = listOf(
                    SyncJournalView(
                        id = 501L,
                        userId = 1L,
                        clientId = store.journal(501L)?.clientId,
                        entryDate = "2026-11-26",
                        mood = "开心",
                        tags = listOf("远端"),
                        content = "远端并发修改",
                        status = 1,
                        version = 1L,
                    )
                ),
            )
            store.markConflict(store.pending().single(), conflictPull)
            val scopedConfirmation = store.confirmations().single()
            assertEquals("instance\n1", scopedConfirmation.scope)

            // Switching to instance\n2 hides instance\n1 confirmation and blocks resolution
            activeScope = "instance\n2"
            assertTrue(store.confirmations().isEmpty())
            assertTrue(runCatching { store.resolveConfirmation(scopedConfirmation, keepMine = false) }.isFailure)

            // Resolving in owning scope succeeds
            activeScope = "instance\n1"
            store.resolveConfirmation(scopedConfirmation, keepMine = false)
            assertTrue(store.confirmations().isEmpty())
            assertEquals("远端并发修改", store.journal(501L)?.content)

            // Guest merge: equivalent positive IDs fold; divergent same-clientId guest row survives; pending positive-ID rebinds by clientId & flags conflict
            val guestScope = "local\nlocal"
            db.journalSnapshotDao().upsertAll(
                listOf(
                    JournalSnapshotEntity(501L, 0L, store.journal(501L)?.clientId, "2026-11-26", "开心", """["远端"]""", "远端并发修改", 1, 1L, scope = guestScope),
                    JournalSnapshotEntity(701L, 0L, "dup-diff-cid", "2026-11-26", "平静", null, "游客分歧正文", 1, 0L, scope = guestScope),
                )
            )
            db.journalSnapshotDao().upsertAll(
                listOf(
                    JournalSnapshotEntity(801L, 1L, "dup-diff-cid", "2026-11-26", "专注", null, "账号分歧正文", 1, 1L, scope = activeScope),
                )
            )
            // Offline pending edit on guest row 702 that gets deduplicated on server to 802
            val pendingGuest = store.forScope(guestScope)
            db.journalSnapshotDao().upsertAll(
                listOf(
                    JournalSnapshotEntity(702L, 0L, "rebind-cid", "2026-11-26", "平静", null, "原正文本", 1, 0L, scope = guestScope),
                )
            )
            pendingGuest.saveJournalChange("2026-11-26", "平静", emptyList(), "游客离线编辑", id = 702L)

            store.acceptGuestData(listOf(guestScope))
            val contents = store.journals().map { it.content }
            assertTrue("远端并发修改" in contents)
            assertTrue("游客分歧正文" in contents)
            assertTrue("账号分歧正文" in contents)

            // Reconcile against full pull where server removed 702 (merged to 802 with version 2) and rekeyed 701
            store.saveSnapshots(
                plans = emptyList(),
                strategies = emptyList(),
                schedules = emptyList(),
                journals = listOf(
                    SyncJournalView(501L, 1L, store.journal(501L)?.clientId, "2026-11-26", "开心", listOf("远端"), "远端并发修改", 1, 1L),
                    SyncJournalView(701L, 1L, "m-701-rekeyed", "2026-11-26", "平静", emptyList(), "游客分歧正文", 1, 1L),
                    SyncJournalView(801L, 1L, "dup-diff-cid", "2026-11-26", "专注", emptyList(), "账号分歧正文", 1, 1L),
                    SyncJournalView(802L, 1L, "rebind-cid", "2026-11-26", "开心", emptyList(), "云端版本2", 1, 2L),
                ),
            )
            val reboundConflict = store.confirmations().single { it.kind == "journal" }
            assertEquals(802L, reboundConflict.id)
            assertEquals("游客离线编辑", reboundConflict.localJournal?.content)
            assertEquals("云端版本2", reboundConflict.remoteJournal?.content)
        }
    }
}
