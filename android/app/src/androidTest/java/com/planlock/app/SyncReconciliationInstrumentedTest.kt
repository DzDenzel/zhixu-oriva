package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.record.*
import com.planlock.app.record.entity.*
import com.planlock.app.api.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated real SQLite; complete-table assertions, never production data. */
class SyncReconciliationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "com.planlock.qapack3a")
    }
    private fun plan(id: Long) = PlanSnapshotEntity(id,1,"plan-$id","study","10:00:00","11:00:00",null,null,null,15,null,1,0)
    private fun schedule(id: Long) = ScheduleSnapshotEntity(id,1,"schedule-$id","2026-10-13","10:00:00","2026-10-13","11:00:00",false,null,null,15,null,null,false,"Asia/Shanghai",1,0)
    private suspend fun withStore(block: suspend (RecordStore,PlanLockDatabase) -> Unit) {
        val name="reconcile-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,PlanLockDatabase::class.java,name).addMigrations(AccountScopeMigration({ "legacy" }), PlanTypeMigration, BehaviorHistoryMigration, ScheduleLocationMigration).build()
        try { block(RecordStore(db, historyScope = { "local\nlocal" }),db) } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun completeEmptyPullRemovesConfirmedRowsAndStrategiesButKeepsDrafts() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan(41)); store.saveLocalPlan(plan(-41))
        store.saveLocalStrategy(StrategySnapshotEntity(51,41,null,"[]",true,false,0))
        store.saveLocalSchedule(schedule(61)); store.saveLocalSchedule(schedule(-61))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(listOf(-41L),store.plans().map { it.id })
        assertEquals(listOf(-61L),store.schedules().map { it.id })
        assertTrue(db.strategySnapshotDao().all("local\nlocal").isEmpty())
    } }
    @Test fun pendingDeleteAlreadyMissingFromCompletePullIsAcknowledged() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan(41)); store.saveLocalSchedule(schedule(61))
        store.deletePlan(41); store.enqueueScheduleDelete(61)
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertTrue(store.pending().isEmpty())
        assertTrue(store.plans().isEmpty()); assertTrue(store.schedules().isEmpty())
    } }
    @Test fun missingEditIsDurableConfirmationAndNeverARetryFailure() = runBlocking {
        val name="restart-confirm-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,PlanLockDatabase::class.java,name).addMigrations(AccountScopeMigration({ "legacy" }), PlanTypeMigration, BehaviorHistoryMigration, ScheduleLocationMigration).build()
        try {
            var store=RecordStore(db, historyScope = { "local\nlocal" })
            store.updateLocalPlan(plan(41).copy(name="my-edit"))
            store.saveSnapshots(emptyList(),emptyList(),emptyList())
            assertTrue(store.pending().isEmpty())
            assertEquals("my-edit",store.confirmations().single().localPlan!!.name)
            db.close(); db=Room.databaseBuilder(context,PlanLockDatabase::class.java,name).addMigrations(AccountScopeMigration({ "legacy" }), PlanTypeMigration, BehaviorHistoryMigration, ScheduleLocationMigration).build(); store=RecordStore(db, historyScope = { "local\nlocal" })
            assertTrue(store.confirmations().single().remoteDeleted)
            store.resolveConfirmation(store.confirmations().single(),false)
            assertTrue(store.plans().isEmpty()); assertTrue(store.confirmations().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun restoreMissingPlanUsesNewIdentityAndRetainsStrategyAndLinkedScheduleIntent() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan(41).copy(name="my-plan"),strategy=StrategySnapshotEntity(51,41,null,"[]",true,false,0))
        val local=schedule(-61).copy(linkedPlanId=41)
        store.saveScheduleChange(local,ScheduleUpsertRequest(local.title,local.startDate,local.startTime,local.endDate,local.endTime,linkedPlanId=41))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        store.resolveConfirmation(store.confirmations().single(),true)
        val restored=store.plans().single()
        assertTrue(restored.id<0); assertEquals("my-plan",restored.name)
        assertEquals(restored.id,store.schedules().single().linkedPlanId)
        assertNotNull(store.strategy(restored.id))
        val queued=store.pending().first { it.type=="plan_upsert" }
        val item=kotlinx.serialization.json.Json.decodeFromString<SyncPlanItem>(queued.payload!!)
        assertNull(item.id); assertNotNull(item.clientId)
        assertTrue(store.confirmations().isEmpty())
    } }
    @Test fun batchDeletionNeedsStrictThresholdAndApprovalOfSameCandidateSet() = runBlocking { withStore { store,_ ->
        (1L..12L).forEach { store.saveLocalPlan(plan(it)) }
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(12,store.plans().size)
        val expected=store.bulkDeletion()!!; assertEquals(12,expected.count)
        store.approveBulkDeletion(expected)
        // A newer local edit invalidates the old candidate set; it must survive.
        store.updateLocalPlan(plan(1).copy(name="new-edit"))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(12,store.plans().size); assertFalse(store.bulkDeletion()!!.approved)
        store.approveBulkDeletion(store.bulkDeletion()!!)
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(listOf(1L),store.plans().map { it.id })
        assertEquals("new-edit",store.confirmations().single().localPlan!!.name)
    } }
    @Test fun olderDialogCannotDiscardNewLocalEdit() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan(41).copy(name="first"))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        val old=store.confirmations().single()
        store.updateLocalPlan(plan(41).copy(name="newer"))
        assertTrue(runCatching { store.resolveConfirmation(old,false) }.isFailure)
        assertEquals("newer",store.plan(41)!!.name)
        assertEquals("newer",store.confirmations().single().localPlan!!.name)
        store.resolveConfirmation(store.confirmations().single(),false)
        assertTrue(store.plans().isEmpty())
    } }
    @Test fun acceptingRemotePlanDoesNotDetachPendingLinkedSchedule() = runBlocking { withStore { store,_ ->
        store.updateLocalPlan(plan(41).copy(name="mine"))
        val local=schedule(-61).copy(linkedPlanId=41)
        store.saveScheduleChange(local,ScheduleUpsertRequest(local.title,local.startDate,local.startTime,local.endDate,local.endTime,linkedPlanId=41))
        val remote=SyncPullResponse(plans=listOf(SyncPlanView(id=41,userId=1,name="theirs",version=2)),strategies=emptyList(),schedules=emptyList())
        store.markConflict(store.pending().first { it.type=="plan_upsert" },remote)
        store.resolveConfirmation(store.confirmations().single(),false)
        assertEquals("theirs",store.plan(41)!!.name)
        assertEquals(41L,store.schedules().single().linkedPlanId)
        val queued=kotlinx.serialization.json.Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!)
        assertEquals(41L,queued.request.linkedPlanId)
    } }
    @Test fun incompleteOrForeignOwnerPullCannotClearAnyData() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan(41))
        val incomplete=object: com.planlock.app.api.PlanLockApi by com.planlock.app.api.mock.MockApi() {
            override suspend fun syncPull()=ApiResponse(data=SyncPullResponse(plans=emptyList()))
        }
        assertTrue(runCatching { com.planlock.app.sync.SyncClient(incomplete,store).pull() }.isFailure)
        val wrong=object: com.planlock.app.api.PlanLockApi by com.planlock.app.api.mock.MockApi() {
            override suspend fun syncPull()=ApiResponse(data=SyncPullResponse(plans=listOf(SyncPlanView(id=44,userId=2)),schedules=emptyList(),strategies=emptyList()))
        }
        assertTrue(runCatching { com.planlock.app.sync.SyncClient(wrong,store,owner={1}).pull() }.isFailure)
        assertEquals(listOf(41L),store.plans().map { it.id })
    } }
    @Test fun scheduleCleanupCancelsItsAlarm() = runBlocking { withStore { original,db ->
        val cancelled=mutableListOf<Long>()
        val store=RecordStore(db, historyScope = { "local\nlocal" }) { cancelled+=it }
        original.saveLocalSchedule(schedule(61))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(listOf(61L),cancelled)
    } }
    @Test fun versionFiveMigrationKeepsDraftAndOutbox() = runBlocking {
        val name="migration-${UUID.randomUUID()}.db"
        val old=context.openOrCreateDatabase(name,0,null)
        try {
            val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.planlock.app.record.PlanLockDatabase/5.json").bufferedReader().readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO plan_snapshot (id,userId,name,type,version) VALUES (-41,1,'old-draft','study',0)")
            old.execSQL("INSERT INTO pending_record (id,type,planId,clientId,createdAt) VALUES (1,'plan_upsert',-41,'old-key',1)")
            old.version=5
        } finally { old.close() }
        val db=Room.databaseBuilder(context,PlanLockDatabase::class.java,name).addMigrations(AccountScopeMigration({ "legacy" }), PlanTypeMigration, BehaviorHistoryMigration, ScheduleLocationMigration).build()
        try {
            assertEquals("old-draft",db.planSnapshotDao().all("legacy\n1").single().name)
            assertEquals("old-key",db.pendingRecordDao().all("legacy\n1").single().clientId)
            assertNull(db.pendingRecordDao().all("legacy\n1").single().baseVersion)
            assertNull(db.pendingRecordDao().all("legacy\n1").single().localSnapshot)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun allConflictChoicesConvergeWithoutAutomaticRetryOrDuplicates() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        for(kind in listOf("plan","schedule")) for(scenario in listOf("edit-edit","edit-delete","delete-edit")) for(mine in listOf(false,true)) withStore { store,_ ->
            android.util.Log.i("ConflictQA", "case=$kind/$scenario/$mine begin")
            val remote=DurableOutboxInstrumentedTest.Remote()
            remote.plans[41]=SyncPlanView(41,1,"plan-41","study","10:00:00","11:00:00",remindAdvanceMin=15,status=1,version=0)
            remote.schedules[51]=ScheduleDto(51,1,"schedule-51","2026-10-13","10:00:00","2026-10-13","11:00:00",false,remindAdvanceMin=15,tzId="Asia/Shanghai",version=0)
            store.saveLocalPlan(plan(41)); store.saveLocalSchedule(schedule(51))
            if(kind=="plan") {
                if(scenario=="delete-edit") store.deletePlan(41) else store.updateLocalPlan(plan(41).copy(name="mine"))
                if(scenario=="edit-delete") remote.plans.remove(41) else remote.plans[41]=remote.plans[41]!!.copy(name="theirs",version=1)
            } else {
                if(scenario=="delete-edit") store.enqueueScheduleDelete(51)
                else store.saveScheduleChange(schedule(51).copy(title="mine"),ScheduleUpsertRequest("mine","2026-10-13","10:00:00","2026-10-13","11:00:00",remindAdvanceMin=15,tzId="Asia/Shanghai"))
                if(scenario=="edit-delete") remote.schedules.remove(51) else remote.schedules[51]=remote.schedules[51]!!.copy(title="theirs",version=1)
            }
            val client=com.planlock.app.sync.SyncClient(remote,store)
            client.pushPendingRecords(); client.pull()
            val conflict=store.confirmations().single()
            assertEquals(kind,conflict.kind); assertEquals(scenario=="delete-edit",conflict.deleting)
            assertEquals(scenario=="edit-delete",conflict.remoteDeleted)
            assertTrue(store.pending().isEmpty())
            repeat(2) { client.pushPendingRecords() }
            assertEquals(conflict,store.confirmations().single())
            store.enqueueExecution(ExecutionRecordRequest(41,"2026-10-13",1,clientId="other-record"))
            client.pushPendingRecords(); assertTrue(store.pending().isEmpty())
            store.resolveConfirmation(conflict,mine)
            android.util.Log.i("ConflictQA", "case=$kind/$scenario/$mine resolved")
            client.pushPendingRecords(); client.pull()
            android.util.Log.i("ConflictQA", "case=$kind/$scenario/$mine synchronized")
            assertTrue(store.confirmations().isEmpty()); assertTrue(store.pending().isEmpty())
            val expected=if(mine && scenario=="delete-edit" || !mine && scenario=="edit-delete") null else if(mine) "mine" else "theirs"
            if(kind=="plan") {
                assertEquals(expected,remote.plans.values.singleOrNull()?.name)
                assertEquals(expected,store.plans().singleOrNull()?.name)
                assertEquals(remote.plans.keys,store.plans().map { it.id }.toSet())
                if (mine && scenario=="edit-delete") assertEquals(store.plans().single().id,store.resolvePlanId(41))
            } else {
                assertEquals(expected,remote.schedules.values.singleOrNull()?.title)
                assertEquals(expected,store.schedules().singleOrNull()?.title)
                assertEquals(remote.schedules.keys,store.schedules().map { it.id }.toSet())
                if (mine && scenario=="edit-delete") assertEquals(store.schedules().single().id,store.resolveScheduleId(51))
            }
        }
    }
    @Test fun remoteChangedAgainRequiresANewChoiceAndKeepsLocalPayload() = runBlocking { withStore { store,_ ->
        val remote=DurableOutboxInstrumentedTest.Remote()
        remote.plans[41]=SyncPlanView(41,1,"other1","study","10:00:00","11:00:00",remindAdvanceMin=15,status=1,version=1)
        store.updateLocalPlan(plan(41).copy(name="mine"))
        val client=com.planlock.app.sync.SyncClient(remote,store)
        client.pushPendingRecords()
        store.resolveConfirmation(store.confirmations().single(),true)
        remote.plans[41]=remote.plans[41]!!.copy(name="other2",version=2)
        client.pushPendingRecords()
        assertEquals("mine",store.confirmations().single().localPlan!!.name)
        assertEquals("other2",store.confirmations().single().remotePlan!!.name)
        assertEquals("other2",remote.plans[41]!!.name)
    } }

    @Test fun batchProtectionAlsoRetainsExecutionStrategiesUntilApproval() = runBlocking { withStore { store,db ->
        (1L..12L).forEach { store.saveLocalPlan(plan(it)); store.saveLocalStrategy(StrategySnapshotEntity(100+it,it,null,"[]",true,true,0)) }
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals(12,db.strategySnapshotDao().all("local\nlocal").size)
        store.approveBulkDeletion(store.bulkDeletion()!!)
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertTrue(db.strategySnapshotDao().all("local\nlocal").isEmpty())
    } }
    @Test fun earlierDeleteCannotAcknowledgeAnEditSavedAfterIt() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan(41)); store.saveLocalSchedule(schedule(51))
        store.deletePlan(41); store.enqueueScheduleDelete(51)
        store.updateLocalPlan(plan(41).copy(name="after-delete"))
        store.saveScheduleChange(schedule(51).copy(title="after-delete"),ScheduleUpsertRequest("after-delete","2026-10-13","10:00:00","2026-10-13","11:00:00"))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        assertEquals("after-delete",store.plan(41)!!.name)
        assertEquals("after-delete",store.schedule(51)!!.title)
        assertEquals(2,store.confirmations().size)
    } }
    @Test fun remoteDeleteDetachesStaleScheduleEditors() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan(41))
        val captured=schedule(-61).copy(linkedPlanId=41)
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        store.saveScheduleChange(captured,ScheduleUpsertRequest(captured.title,captured.startDate,captured.startTime,captured.endDate,captured.endTime,linkedPlanId=41))
        assertNull(store.schedule(-61)!!.linkedPlanId)
        val item=kotlinx.serialization.json.Json.decodeFromString<SchedulePendingUpsert>(store.pending().single().payload!!)
        assertNull(item.request.linkedPlanId)
    } }
    @Test fun keepMineRetainsTheWholeDisplayedExecutionSettings() = runBlocking { withStore { store,_ ->
        store.saveLocalStrategy(StrategySnapshotEntity(61,41,null,"[]",true,true,0))
        store.updateLocalPlan(plan(41).copy(name="my-title"))
        val remote=SyncPullResponse(plans=listOf(SyncPlanView(id=41,userId=1,name="other-title",version=1)),strategies=listOf(SyncStrategyView(61,41,null,"[]",false,false,1)),schedules=emptyList())
        store.markConflict(store.pending().single(),remote)
        store.resolveConfirmation(store.confirmations().single(),true)
        assertEquals(true,store.strategy(41)!!.autoDnd)
        assertEquals(true,store.strategy(41)!!.notifySilence)
        val item=kotlinx.serialization.json.Json.decodeFromString<SyncStrategyItem>(store.pending().first { it.type=="strategy_upsert" }.payload!!)
        assertEquals(1L,item.baseVersion)
    } }
    @Test fun restoringDeletedPlanMovesPendingTelemetryAndAggregates() = runBlocking { withStore { store,db ->
        store.updateLocalPlan(plan(41).copy(name="my-title"))
        store.enqueueExecution(ExecutionRecordRequest(41,"2026-10-13",1,clientId="offline-execution"))
        store.enqueueViolation(ViolationRecordRequest(41,"2026-10-13T10:00:00",packageName="test",startedAt="2026-10-13T10:00:00",endedAt="2026-10-13T10:01:00",durationMs=60000,clientId="offline-violation"))
        store.enqueueUnlock(UnlockRecordRequest(41,"2026-10-13T10:01:00",clientId="offline-unlock"))
        store.saveSnapshots(emptyList(),emptyList(),emptyList())
        store.resolveConfirmation(store.confirmations().single(),true)
        val id=store.plans().single().id
        assertTrue(id<0)
        assertTrue(store.pending().filter { it.type in setOf("execution","violation","unlock") }.all { it.planId==id })
        assertEquals(id,db.behaviorAggregateDao().all("local\nlocal").single().planId)
    } }

}
