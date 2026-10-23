package com.planlock.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.mock.MockApi
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.feature.plan.PlanActionGate
import com.planlock.feature.plan.PlanActionResult
import com.planlock.feature.plan.ProtectedPlanAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopeBoundaryInstrumentedTest {
    @Test fun staleScheduleDeleteCannotDeleteTheOtherAccountSameId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            var scope = "instance-a\n1"
            val store = RecordStore(db,{scope})
            val first = com.planlock.app.record.entity.ScheduleSnapshotEntity(1,1,"A","2026-10-19",null,"2026-10-19",null,true,null,null,null,null,null,false,null,1,0)
            store.saveLocalSchedule(first)
            val old = store.schedules().single()
            scope = "instance-b\n1"
            store.saveLocalSchedule(first.copy(title="B"))
            assertTrue(runCatching { store.enqueueScheduleDelete(old.id,old.scope) }.isFailure)
            assertEquals("B",store.schedules().single().title)
            assertTrue(store.pending().isEmpty())
            assertEquals("A",store.forScope(old.scope).schedules().single().title)
        } finally { db.close() }
    }
    @Test fun pluginPlanQueryRejectsInstanceChangeEvenWhenNumericOwnerMatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            val writer = RecordStore(db, { "instance-a\n1" })
            writer.saveLocalPlan(PlanSnapshotEntity(1, 1, "A-private", "study", "09:00", "10:00", null, null, null, 0, null, 1, 0))
            var reads = 0
            val store = RecordStore(db, { if (++reads == 1) "instance-a\n1" else "instance-b\n1" })
            assertTrue(runCatching { CachedPluginQueries(context, store).queryPlan(1) }.isFailure)
        } finally { db.close() }
    }

    @Test fun pluginReportQueryRejectsInstanceChangeEvenWhenNumericOwnerMatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            var reads = 0
            val store = RecordStore(db, { if (++reads == 1) "instance-a\n1" else "instance-b\n1" })
            assertTrue(runCatching { CachedPluginQueries(context, store).queryReport("week") }.isFailure)
        } finally { db.close() }
    }

    @Test fun identityChangeDuringActionPreflightCannotDeleteOtherScopeSameId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preferences = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { entered.complete(Unit); release.await(); emit(emptyPreferences()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
        }
        try {
            var scope = "instance-a\n1"
            val store = RecordStore(db, { scope })
            store.saveLocalPlan(PlanSnapshotEntity(1, 1, "A", "study", "09:00", "10:00", null, null, null, 0, null, 1, 0))
            val accountA = store.plans().single()
            scope = "instance-b\n1"
            store.saveLocalPlan(accountA.copy(name = "B"))
            scope = accountA.scope
            val gate = PlanActionGate(store, MockApi(), BackupPasswordStore(preferences), preferences)
            val operation = async { gate.perform(accountA, ProtectedPlanAction.DELETE, null) }
            entered.await()
            scope = "instance-b\n1"
            release.complete(Unit)
            assertTrue(operation.await() is PlanActionResult.Failed)
            assertEquals("B", store.plans().single().name)
            assertTrue(store.pending().isEmpty())
            assertEquals("A", store.forScope(accountA.scope).plans().single().name)
        } finally { db.close() }
    }
}
