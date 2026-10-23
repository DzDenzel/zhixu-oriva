package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.edit
import com.planlock.app.api.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.SyncClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.UUID

/** Two independent Room clients and two real backend device identities, on the shared AVD. */
class ExecutionHistoryRealSyncTest {
    @Test fun twoDevicesConvergeAndUploadNeverChangesLocalCounts() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.planlock.qapack3a")
        val input=Json.parseToJsonElement(context.filesDir.resolve("history-qa-sessions.json").readText()).jsonObject
        fun string(key:String)=input.getValue(key).jsonPrimitive.content
        val owner=input.getValue("userId").jsonPrimitive.long
        context.planLockPreferences.edit { it.clear() }
        TokenStore.restore(context.planLockPreferences)
        TokenStore.changeConnection(context.planLockPreferences,ConnectionConfig(instanceLabel="F101 isolated QA",apiBaseUrl=string("apiBaseUrl"),pluginsCatalogUrl=string("apiBaseUrl")+"plugins/catalog/",lanAllowed=true))
        suspend fun device(which:String) { TokenStore.save(context.planLockPreferences,AuthSession(token=string("token$which"),userId=owner,deviceId=input.getValue("device${which}Id").jsonPrimitive.long)) }
        device("A")
        val api=ApiClient.create(false)
        val names=listOf("f101-a-${UUID.randomUUID()}.db","f101-b-${UUID.randomUUID()}.db")
        var aDb=Room.databaseBuilder(context,PlanLockDatabase::class.java,names[0]).build()
        val bDb=Room.databaseBuilder(context,PlanLockDatabase::class.java,names[1]).build()
        fun clock(dayOffset:Int,hour:Int,minute:Int=0)=Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH,dayOffset);set(Calendar.HOUR_OF_DAY,hour);set(Calendar.MINUTE,minute);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0) }.timeInMillis
        try {
            var a=RecordStore(aDb,historyScope={"real:$owner"})
            val b=RecordStore(bDb,historyScope={"real:$owner"})
            val ca=SyncClient(api,a,owner={owner})
            val cb=SyncClient(api,b,owner={owner})
            val p=PlanSnapshotEntity(-7101,owner,"F101 completed","study","09:00","10:00",null,null,null,15,null,1,0)
            a.updateLocalPlan(p)
            a.updateLocalPlan(p.copy(id=-7102,name="F101 early",startTime="11:00",endTime="12:00"))
            assertTrue(a.beginOccurrence(-7101,clock(0,9)))
            assertTrue(a.finishOccurrence(-7101,clock(0,10)))
            assertTrue(a.beginOccurrence(-7102,clock(0,11)))
            assertTrue(a.endOccurrence(-7102,clock(0,11,30),false,"user_ended"))
            fun count(rows:List<com.planlock.app.api.dto.ExecutionRecordDto>)=rows.count { it.status==1||it.status==2 }
            assertEquals(2,count(a.executionHistory()))
            ca.pushPendingRecords();ca.pull();ca.pullHistory()
            assertEquals(2,count(a.executionHistory()));assertTrue(a.pending().isEmpty())
            assertTrue(a.executionHistory().filter { it.status in listOf(1,2) }.all { it.id>0 })
            device("B");cb.pull();cb.pullHistory()
            assertEquals(2,count(b.executionHistory()))
            val early=a.resolvePlanId(-7102)
            assertFalse(b.beginOccurrence(early,clock(0,11,45)))
            assertTrue(b.beginOccurrence(early,clock(1,11)))
            device("A")
            assertTrue(a.beginOccurrence(early,clock(1,11)))
            assertTrue(a.endOccurrence(early,clock(1,11,30),true,"user_ended"))
            ca.pushPendingRecords()
            device("B");cb.pull();cb.pullHistory()
            assertEquals(0,b.plan(early)!!.status)
            device("A");a.setPlanEnabled(early,true,clock(2,8));ca.pushPendingRecords()
            device("B");cb.pull()
            assertEquals(1,b.plan(early)!!.status);assertTrue(b.beginOccurrence(early,clock(2,11)))
            val before=count(a.executionHistory())
            aDb.close();aDb=Room.databaseBuilder(context,PlanLockDatabase::class.java,names[0]).build();a=RecordStore(aDb,historyScope={"real:$owner"})
            assertEquals(before,count(a.executionHistory()))
            assertTrue(RecordStore(aDb,historyScope={"other-instance:$owner"}).executionHistory().isEmpty())
            context.filesDir.resolve("history-real-sync-proof.json").writeText("""{"uploadBefore":2,"uploadAfter":2,"deviceBReceived":2,"todaySkipped":true,"tomorrowStarted":true,"entirePlanPaused":true,"resumeStarted":true,"restartCount":$before,"otherScopeEmpty":true}""")
        } finally { aDb.close();bDb.close();names.forEach(context::deleteDatabase) }
    }
}
