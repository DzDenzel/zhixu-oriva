package com.planlock.app

import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.*
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.*
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real account authentication, successful merge, retained outbox and server owner checks. */
class AccountScopeRealSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "com.planlock.qapack3a") }
    private fun input() = Json.parseToJsonElement(context.filesDir.resolve("account-qa.json").readText()).jsonObject
    private suspend fun login(which: String) {
        TokenStore.restore(context.planLockPreferences)
        val input = input()
        val response = ApiClient.create(false).login(LoginRequest(input.getValue("account$which").jsonPrimitive.content,
            password = input.getValue("password").jsonPrimitive.content, deviceUid = "account-scope-shared-device", platform = "android"))
        assertEquals(0, response.code)
        AppSync.get(context).commitLogin(requireNotNull(response.data), ConnectionStore.state.value)
    }
    private fun plan(id: Long, name: String) = PlanSnapshotEntity(id, 0, name, "study", "09:00", "10:00", null, null, null, 0, null, 1, 0)
    private suspend fun schedule(store: RecordStore, id: Long, name: String) {
        val req = ScheduleUpsertRequest(name, "2026-10-18", null, "2026-10-18", null, true)
        store.saveScheduleChange(ScheduleSnapshotEntity(id,0,name,req.startDate,null,req.endDate,null,true,null,null,null,null,null,false,null,1,0),req)
    }

    @Test fun firstMergeAccountSwitchGuestChoiceAndDeferredUploadAreIsolated() = runBlocking {
        val input = input()
        context.planLockPreferences.edit { it.clear() }
        TokenStore.restore(context.planLockPreferences)
        val db = PlanLockDatabase.get(context)
        db.clearAllTables()
        val store = RecordStore(context)
        store.updateLocalPlan(plan(-108001, "A-guest-plan-1"))
        store.updateLocalPlan(plan(-108002, "A-guest-plan-2"))
        fun clock(hour: Int) = java.util.Calendar.getInstance().apply { clear(); set(2026,9,18,hour,0) }.timeInMillis
        assertTrue(store.beginOccurrence(-108001,clock(9)))
        assertTrue(store.finishOccurrence(-108001,clock(10)))
        schedule(store,-108003,"A-guest-schedule-1");schedule(store,-108004,"A-guest-schedule-2")
        val url = input.getValue("apiBaseUrl").jsonPrimitive.content
        TokenStore.changeConnection(context.planLockPreferences, ConnectionConfig(instanceLabel="Account QA",apiBaseUrl=url,pluginsCatalogUrl=url+"plugins/catalog/",lanAllowed=true))
        val sync = AppSync.get(context)
        login("A")
        assertNull(sync.guestMergeOffer.value)
        assertEquals(2,store.plans().size);assertEquals(2,store.schedules().size)
        assertTrue(store.forScope("local\nlocal").plans().isEmpty())
        assertTrue(store.forScope("local\nlocal").schedules().isEmpty())
        sync.pushPendingRecords()
        val a = TokenStore.userId!!
        val remoteA = requireNotNull(ApiClient.create(false).syncPull().data)
        assertEquals(2,remoteA.plans!!.size);assertEquals(2,remoteA.schedules!!.size)
        repeat(12) { store.updateLocalPlan(plan(-108100-it.toLong(), "A-offline-$it")) }
        assertEquals(12,store.pending().size)
        sync.withSessionChange { TokenStore.signOut(context.planLockPreferences) }
        assertTrue(store.plans().isEmpty());assertTrue(store.schedules().isEmpty());assertTrue(store.executionHistory().isEmpty())
        store.updateLocalPlan(plan(-108900,"G-retained"))
        login("B")
        assertEquals(1,sync.guestMergeOffer.value!!.count)
        sync.chooseGuestMerge(false)
        assertTrue(store.plans().isEmpty());assertTrue(store.pending().isEmpty())
        store.updateLocalPlan(plan(-108001,"B-only-plan"));schedule(store,-108003,"B-only-schedule")
        sync.pushPendingRecords()
        val b = TokenStore.userId!!
        assertNotEquals(a,b)
        val remoteB = requireNotNull(ApiClient.create(false).syncPull().data)
        assertEquals(listOf("B-only-plan"),remoteB.plans!!.map { it.name })
        assertEquals(listOf("B-only-schedule"),remoteB.schedules!!.map { it.title })
        assertEquals(listOf("B-only-plan"),loadAssistantSnapshot(store,sync).plans.map { it.name })
        assertTrue(store.behaviorAggregates().isEmpty());assertTrue(store.executionHistory().isEmpty())
        sync.withSessionChange { TokenStore.signOut(context.planLockPreferences) }
        assertEquals(listOf("G-retained"),store.plans().map { it.name })
        login("A");sync.chooseGuestMerge(false)
        assertEquals(14,store.plans().size);assertEquals(2,store.schedules().size)
        assertEquals(1,store.executionHistory().count { it.status == 1 })
        assertEquals(12,store.pending().size)
        assertTrue(store.plans().none { it.name.startsWith("B-") || it.name.startsWith("G-") })
        sync.pushPendingRecords()
        assertTrue(store.pending().isEmpty())
        val finalA = requireNotNull(ApiClient.create(false).syncPull().data)
        assertEquals(14,finalA.plans!!.size);assertEquals(2,finalA.schedules!!.size)
        assertTrue(finalA.plans!!.all { it.userId == a });assertTrue(finalA.schedules!!.all { it.userId == a })
        context.filesDir.resolve("account-real-proof.json").writeText("""{"firstMergePlans":2,"firstMergeSchedules":2,"guestSpaceCleared":true,"accountA":$a,"accountB":$b,"deferredAIntents":12,"bSawA":false,"bServerPlans":1,"bServerSchedules":1,"guestChoiceRetained":true,"aRestoredPlans":14,"aUploadedPlans":14,"aUploadedSchedules":2,"pendingAfterUpload":0}""",Charsets.UTF_8)
    }

    @Test fun selectBForUi() = runBlocking { login("B"); AppSync.get(context).chooseGuestMerge(false) }
    @Test fun selectAForUi() = runBlocking { login("A"); AppSync.get(context).chooseGuestMerge(false) }
    @Test fun selectGuestForUi() = runBlocking { TokenStore.restore(context.planLockPreferences); AppSync.get(context).withSessionChange { TokenStore.signOut(context.planLockPreferences) } }

    @Test fun prepareSlowSaveScenario() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val url = input().getValue("apiBaseUrl").jsonPrimitive.content.replace(":18081/", ":18082/")
        val sync = AppSync.get(context)
        sync.withSessionChange { TokenStore.changeConnection(context.planLockPreferences, ConnectionConfig(instanceLabel="Slow QA",apiBaseUrl=url,pluginsCatalogUrl=url+"plugins/catalog/",lanAllowed=true)) }
        login("A");sync.chooseGuestMerge(false)
        sync.coordinator.refresh().join()
        assertNull(sync.coordinator.state.value.error)
        val store = RecordStore(context)
        repeat(12) { store.updateLocalPlan(plan(-108600-it.toLong(),"slow-backlog-$it")) }
        assertEquals(12,store.pending().size)
    }

    @Test fun verifySlowSaveRecovery() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        AppSync.get(context).pushPendingRecords()
        assertTrue(store.pending().isEmpty())
        val remote = ApiClient.create(false).syncPull().data!!
        assertTrue(remote.schedules!!.any { it.title == "QA-SAVE-NETWORK-108" })
        assertTrue(remote.plans!!.count { it.name.startsWith("slow-backlog-") } >= 12)
        context.filesDir.resolve("slow-save-recovery.json").writeText("""{"pending":0,"newScheduleOnServer":true,"backlogPlansOnServer":12}""",Charsets.UTF_8)
    }
}
