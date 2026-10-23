package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.mock.MockApi
import com.planlock.app.sync.SyncClient

/** In-memory databases only; no account, installed database, or backend is touched. */
class PlanTypesInstrumentedTest {
    private fun plan(type: String) = PlanSnapshotEntity(-31, 1, "写作练习", type, "09:00", "10:00", null, null, null, 0, null, 1, 0)

    @Test fun durableTypeQueueRequiresMatchingAcknowledgementAndHydratesAnotherDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            val store = RecordStore(db, { "instance\n1" })
            val key = store.createPlanType("写作", "rose")
            var acknowledgeCorrectly = false
            var value: SyncPlanTypeView? = null
            val remote = object : PlanLockApi by MockApi() {
                override suspend fun syncPush(req: SyncPushRequest): ApiResponse<SyncPushResponse> {
                    val sent = requireNotNull(req.planTypes).single()
                    value = SyncPlanTypeView(sent.typeKey,1,sent.name,sent.color,(value?.version ?: -1) + 1,sent.deleted)
                    return ApiResponse(data = SyncPushResponse(listOf(SyncItemResult(key="planType:${sent.typeKey}",ok=true,version=value!!.version,clientId=if(acknowledgeCorrectly) sent.mutationId else "unrelated"))))
                }
                override suspend fun syncPull() = ApiResponse(data = SyncPullResponse(emptyList(),emptyList(),emptyList(),planTypes=listOfNotNull(value)))
            }
            val client = SyncClient(remote,store,owner={1})
            try { client.pushPendingRecords(); fail("Mismatched acknowledgement must retain outbox") } catch (_: IllegalStateException) { }
            assertTrue(store.pending().any { it.type == "plan_type_upsert" })
            acknowledgeCorrectly = true
            client.pushPendingRecords()
            assertTrue(store.pending().isEmpty())
            val reader = RecordStore(second, { "instance\n1" })
            SyncClient(remote,reader,owner={1}).pull()
            assertEquals("写作", reader.planTypeLabel(key))
            store.renamePlanType(key,"绘画")
            client.pushPendingRecords()
            SyncClient(remote,reader,owner={1}).pull()
            assertEquals("绘画",reader.planTypeLabel(key))
            store.deletePlanType(key)
            client.pushPendingRecords()
            SyncClient(remote,reader,owner={1}).pull()
            assertEquals("其他",reader.planTypeLabel(key))
            assertTrue(reader.planTypes().isEmpty())
        } finally { db.close(); second.close() }
    }

    @Test fun customTypeRenameDeleteAndQueueAreScopedAndAtomic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            var scope = "instance\n1"
            val store = RecordStore(db, { scope })
            val key = store.createPlanType("写作", "rose")
            store.updateLocalPlan(plan(key))
            store.renamePlanType(key, "绘画", "amber")
            assertEquals("绘画", store.planTypeLabel(key))
            assertEquals(key, store.plans().single().type)
            scope = "instance\n2"
            assertTrue(store.planTypes().isEmpty())
            assertTrue(store.pending().isEmpty())
            assertEquals("其他", store.planTypeLabel(key))
            scope = "instance\n1"
            store.deletePlanType(key)
            assertTrue(store.planTypes().isEmpty())
            assertEquals("其他", store.plans().single().type)
            assertTrue(store.pending().filter { it.type == "plan_upsert" }.all { Json.decodeFromString<SyncPlanItem>(it.payload!!).type == "其他" })
            assertTrue(Json.decodeFromString<SyncPlanTypeItem>(store.pending().last().payload!!).deleted)
            // A still-open old editor must not revive the deleted reference.
            store.updateLocalPlan(plan(key))
            assertEquals("其他", store.plans().single().type)
        } finally { db.close() }
    }

    @Test fun firstPullHydratesCatalogAndRemoteDeletionOverridesAnOfflineRename() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            val store = RecordStore(db, { "instance\n1" })
            val remote = SyncPlanTypeView("custom:remote", 1, "写作", "rose", 2)
            store.saveSnapshots(emptyList(), emptyList(), planTypes = listOf(remote))
            assertEquals("写作", store.planTypeLabel("custom:remote"))
            store.updateLocalPlan(plan("custom:remote"))
            store.renamePlanType("custom:remote", "绘画")
            store.saveSnapshots(emptyList(), emptyList(), planTypes = listOf(remote.copy(version = 3, deleted = true)))
            assertEquals("其他", store.plans().single().type)
            assertTrue(store.planTypes().isEmpty())
            assertFalse(store.pending().any { it.type == "plan_type_upsert" })
        } finally { db.close() }
    }

    @Test fun guestMergeTransfersTypeCatalogAndDurableIntentsOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            val guest = RecordStore(db, { "local\nlocal" })
            val key = guest.createPlanType("写作", "rose")
            guest.updateLocalPlan(plan(key))
            val member = guest.forScope("instance\n2")
            member.acceptGuestData(listOf("local\nlocal"))
            assertTrue(guest.planTypes().isEmpty())
            assertTrue(guest.pending().isEmpty())
            assertEquals("写作", member.planTypeLabel(key))
            assertEquals(key, member.plans().single().type)
            assertTrue(member.pending().any { it.type == "plan_type_upsert" })
            member.acceptGuestData(listOf("local\nlocal"))
            assertEquals(1, member.planTypes().size)
        } finally { db.close() }
    }
}
