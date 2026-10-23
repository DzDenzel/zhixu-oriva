package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.UUID

class ExecutionHistoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "com.planlock.qapack3a") { "Isolated QA application required" }
    }
    private fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        clear(); set(2026,9,day,hour,minute)
    }.timeInMillis
    private fun plan() = PlanSnapshotEntity(7,1,"专注","study","23:00","06:00",null,null,null,15,null,1,0)
    private suspend fun withStore(block: suspend (RecordStore, PlanLockDatabase) -> Unit) {
        val name = "history-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context,PlanLockDatabase::class.java,name).build()
        try { block(RecordStore(db, historyScope = { "test:1" }),db) } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun boundedDisplayHistoryIsReadOnlyScopedAndKeepsAcknowledgedTerminals() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan().copy(startTime="09:00",endTime="10:00"))
        for (day in 20..22) {
            assertTrue(store.beginOccurrence(7,at(day,9)))
            assertTrue(store.finishOccurrence(7,at(day,10)))
        }
        db.executionHistoryDao().insert(com.planlock.app.record.entity.ExecutionHistoryEntity(
            "test:1","unlock-test","unlock",planId=7,planDate="2026-10-21",status=1,endedAt="2026-10-21T10:00:00"))
        store.removePending(store.pending().map { it.id })
        val before = db.executionHistoryDao().all("test:1")
        val pendingBefore = store.pending()
        val today = store.executionHistoryForDates("2026-10-21","2026-10-21")
        assertEquals(2,today.size)
        assertTrue(today.all { it.type=="execution" && it.planDate=="2026-10-21" && it.scope=="test:1" })
        assertEquals(1,today.count { it.status==1 })
        assertTrue(store.forScope("test:2").executionHistoryForDates("2026-10-20","2026-10-22").isEmpty())
        assertEquals(before,db.executionHistoryDao().all("test:1"))
        assertEquals(pendingBefore,store.pending())
    } }
    @Test fun concurrentEndAndServiceTickWriteOneTerminalAndKeepHistoryAfterUpload() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan())
        assertTrue(store.beginOccurrence(7,at(16,23)))
        coroutineScope {
            repeat(8) { launch { store.endOccurrence(7,at(17,1),false,"user_ended") } }
            launch { store.beginOccurrence(7,at(17,1)) }
        }
        store.finishOccurrence(7,at(17,6))
        assertEquals(1,store.executionHistory().count { it.status==2 })
        assertEquals("2026-10-16",store.executionHistory().single { it.status==2 }.planDate)
        assertEquals(1,store.unlockHistory().size)
        assertEquals(1,store.plan(7)!!.status)
        assertFalse(store.beginOccurrence(7,at(17,2)))
        val before = store.executionHistory()
        store.removePending(store.pending().map { it.id })
        assertEquals(before,store.executionHistory())
        assertTrue(store.beginOccurrence(7,at(17,23)))
    } }
    @Test fun failedTransactionRollsBackEveryEndWrite() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan()); store.beginOccurrence(7,at(16,23))
        val before=store.pending()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_plan BEFORE INSERT ON plan_snapshot BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertTrue(runCatching { store.endOccurrence(7,at(17,1),true,"user_ended") }.isFailure)
        assertEquals(before,store.pending()); assertEquals(0,store.executionHistory().count { it.status==2 })
        assertTrue(store.unlockHistory().isEmpty()); assertNull(store.plan(7)!!.endedOccurrenceDate)
    } }
    @Test fun naturalMidnightEndUsesDurableStartDateAndScopeIsIsolated() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan()); store.beginOccurrence(7,at(16,23))
        assertTrue(store.finishOccurrence(7,at(17,6)))
        assertEquals("2026-10-16",store.executionHistory().single { it.status==1 }.planDate)
        val other=RecordStore(db,historyScope={"test:2"})
        assertTrue(other.executionHistory().isEmpty())
        store.mergeHistory(RecordHistoryPage(executions=listOf(store.executionHistory().single { it.status==1 }.copy(id=70)),executionCursor=70))
        assertEquals(1,store.executionHistory().count { it.status==1 })
    } }
    @Test fun entirePlanEndAndResumePersistStatusHistoryAndRemoteOccurrenceSkips() = runBlocking { withStore { store,db ->
        store.saveLocalPlan(plan()); store.beginOccurrence(7,at(16,23))
        assertTrue(store.endOccurrence(7,at(17,1),true,"user_ended"))
        assertEquals(0,store.plan(7)!!.status)
        assertTrue(store.plan(7)!!.statusLog!!.contains("2026-10-17"))
        store.setPlanEnabled(7,true,at(18,1))
        assertEquals(1,store.plan(7)!!.status)
        assertTrue(store.beginOccurrence(7,at(18,23)))
        val bName = "history-b-${UUID.randomUUID()}.db"
        val bDb = Room.databaseBuilder(context,PlanLockDatabase::class.java,bName).build()
        val b=RecordStore(bDb,historyScope={"test:1"})
        // Snapshot state alone, without A's execution history, suppresses a remote repeat.
        store.saveLocalPlan(plan().copy(endedOccurrenceDate="2026-10-16"))
        try {
            b.saveLocalPlan(store.plan(7)!!)
            assertTrue(b.executionHistory().isEmpty())
            assertFalse(b.beginOccurrence(7,at(17,1)))
            assertTrue(b.beginOccurrence(7,at(17,23)))
        } finally { bDb.close(); context.deleteDatabase(bName) }
    } }
    @Test fun remoteStartIsStatisticsEvidenceAndCannotBecomeLocalServiceState() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan())
        store.mergeHistory(RecordHistoryPage(executions=listOf(ExecutionRecordDto(id=10,planId=7,deviceId=20,planDate="2026-10-16",status=0,startedAt="2026-10-16T23:00:00",clientId="remote-start")),executionCursor=10))
        assertNull(store.activeExecution(7))
        assertFalse(store.finishOccurrence(7,at(17,6)))
        assertTrue(store.beginOccurrence(7,at(16,23)))
        assertNotEquals("remote-start",store.activeExecution(7)!!.clientId)
        assertTrue(store.finishOccurrence(7,at(17,6)))
        assertEquals(1,store.executionHistory().count { it.status==1 })
    } }
    @Test fun endingAfterLongSleepClosesYesterdayAndSuppressesCurrentOccurrence() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan().copy(startTime="09:00",endTime="10:00"))
        assertTrue(store.beginOccurrence(7,at(16,9)))
        assertTrue(store.endOccurrence(7,at(17,9,30),false,"user_ended"))
        assertEquals("2026-10-17",store.plan(7)!!.endedOccurrenceDate)
        assertFalse(store.beginOccurrence(7,at(17,9,31)))
        val yesterday=store.executionHistory().single { it.status==1 }
        assertEquals("2026-10-16T10:00:00",yesterday.endedAt)
        assertEquals("2026-10-17",store.executionHistory().single { it.status==2 }.planDate)
    } }
    @Test fun remoteTerminalWithoutEndedSnapshotCannotEndLocalRun() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan()); store.beginOccurrence(7,at(16,23))
        store.mergeHistory(RecordHistoryPage(executions=listOf(ExecutionRecordDto(id=10,planId=7,planDate="2026-10-16",status=2,clientId="remote-end")),executionCursor=10))
        assertTrue(store.beginOccurrence(7,at(17,1)))
        assertTrue(store.endOccurrence(7,at(17,1),false,"user_ended"))
        assertEquals("2026-10-16",store.plan(7)!!.endedOccurrenceDate)
        assertEquals(2,store.executionHistory().count { it.status==2 })
    } }
    @Test fun newlyVisibleMergedPlanReplaysOldIdsWithoutLosingExistingHistory() = runBlocking { withStore { store,_ ->
        store.saveLocalPlan(plan())
        store.mergeHistory(RecordHistoryPage(executions=listOf(ExecutionRecordDto(id=100,planId=7,planDate="2026-10-16",status=1,clientId="existing")),executionCursor=100))
        store.saveSnapshots(listOf(SyncPlanView(id=7,userId=1,name="existing"),SyncPlanView(id=8,userId=1,name="merged guest")),emptyList())
        assertEquals(0L,store.historyCursor().executionId)
        store.mergeHistory(RecordHistoryPage(executions=listOf(ExecutionRecordDto(id=50,planId=8,planDate="2026-10-16",status=1,clientId="merged"),ExecutionRecordDto(id=100,planId=7,planDate="2026-10-16",status=1,clientId="existing")),executionCursor=100))
        assertEquals(2,store.executionHistory().size)
    } }
    @Test fun unownedClaimDoesNotMoveGuestHistoryUntilMergeIsExplicitlyAccepted() = runBlocking { withStore { _,db ->
        val guest=RecordStore(db,historyScope={"instance\n11"})
        guest.enqueueExecution(ExecutionRecordRequest(7,"2026-10-16",1,clientId="guest-record"))
        val account=RecordStore(db,historyScope={"instance\n22"})
        account.claimUnownedHistory("instance")
        assertTrue(account.executionHistory().isEmpty())
        assertEquals(1,guest.executionHistory().size)
        account.claimMergedHistory("instance",11)
        assertEquals(1,account.executionHistory().size)
        assertTrue(guest.executionHistory().isEmpty())
    } }
}
