package com.planlock.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.security.BackupPasswordStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only invoked explicitly after taking a byte-for-byte QA backup. */
class ExecutionHistoryUiFixture {
    @Test fun prepareOfflineUiScenario() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        context.planLockPreferences.edit { it.clear(); it[booleanPreferencesKey("backend_local_only")] = true }
        TokenStore.restore(context.planLockPreferences)
        val db=PlanLockDatabase.get(context)
        db.clearAllTables()
        val store=RecordStore(context)
        val now=System.currentTimeMillis()
        fun clock(at: Long) = SimpleDateFormat("HH:mm:00",Locale.ROOT).format(Date(at))
        val complete=PlanSnapshotEntity(-5701,0,"QA-已完成","study",clock(now-30*60_000),clock(now-10*60_000),null,null,null,15,null,1,0)
        store.saveLocalPlan(complete)
        assertTrue(store.beginOccurrence(complete.id,now-29*60_000))
        assertTrue(store.finishOccurrence(complete.id,now-9*60_000))
        store.saveLocalPlan(complete.copy(id=-5702,name="QA-提前收工",startTime=clock(now-2*60_000),endTime=clock(now+60*60_000)))
        assertEquals(1,store.executionHistory().count { it.status==1 })
        assertTrue(store.unlockHistory().isEmpty())
    }
    @Test fun addPasswordGatedEntirePlanScenario() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.planlock.qapack3a")
        TokenStore.restore(context.planLockPreferences)
        BackupPasswordStore(context.planLockPreferences).set("1234")
        context.planLockPreferences.edit { it[PlanLockPreferenceKeys.actionGateEnabled]=true }
        val now=System.currentTimeMillis()
        fun clock(at: Long)=SimpleDateFormat("HH:mm:00",Locale.ROOT).format(Date(at))
        RecordStore(context).saveLocalPlan(PlanSnapshotEntity(-5703,0,"QA-整个计划","study",clock(now-60_000),clock(now+60*60_000),null,null,null,15,null,1,0))
    }
    @Test fun addNaturalCompletionScenario() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.planlock.qapack3a")
        TokenStore.restore(context.planLockPreferences)
        val now=System.currentTimeMillis()
        fun clock(at: Long)=SimpleDateFormat("HH:mm:00",Locale.ROOT).format(Date(at))
        RecordStore(context).saveLocalPlan(PlanSnapshotEntity(-5704,TokenStore.localDataUserId ?: 0,"QA-自然完成","study",clock(now-60_000),clock(now+120_000),null,null,null,15,null,1,0))
        context.filesDir.resolve("natural-end.txt").writeText(clock(now+120_000))
    }
}
