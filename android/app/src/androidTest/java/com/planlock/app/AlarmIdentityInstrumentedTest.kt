package com.planlock.app

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.AuthSession
import com.planlock.app.api.ConnectionConfig
import com.planlock.app.api.ConnectionStore
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.PlanAlarmScheduler
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses actual AlarmManager and session state, without network login or changing saved owner credentials. */
class AlarmIdentityInstrumentedTest {
    @Test fun switchingIdentityReplacesOsAlarmKeysAndRestoringOwnerRestoresOnlyItsAlarms() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.qapack3a")
        val job = SupervisorJob()
        val file = context.preferencesDataStoreFile("alarm-identity-${UUID.randomUUID()}")
        val preferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val registry = context.getSharedPreferences("scoped_plan_alarms", 0)
        val scheduler = PlanAlarmScheduler(context)
        AppSync.get(context).withSessionChange {
            try {
                val connection = ConnectionConfig(instanceLabel = "Isolated alarm QA",
                    apiBaseUrl = "https://alarm-qa.invalid/api/v1/", pluginsCatalogUrl = "https://alarm-qa.invalid/plugins/")
                TokenStore.changeConnection(preferences, connection)
                TokenStore.save(preferences, AuthSession("isolated-qa-token", 901, 901, signedIn = true), ConnectionStore.state.value)
                val ownerA = TokenStore.state.value.dataScope
                val store = RecordStore(db, { TokenStore.state.value.dataScope })
                val now = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis + 86_400_000L
                val plan = PlanSnapshotEntity(-219001, 901, "Alarm identity QA", "study", "10:00", "11:00",
                    null, null, null, 15, null, 1, 0)
                store.updateLocalPlan(plan)
                scheduler.reconcile(store, now)
                assertEquals(3, registry.all.size)
                assertTrue(registry.all.keys.all { it.startsWith("$ownerA\n") })

                TokenStore.save(preferences, AuthSession("isolated-qa-token-b", 902, 902, signedIn = true), ConnectionStore.state.value)
                val ownerB = TokenStore.state.value.dataScope
                assertNotEquals(ownerA, ownerB)
                scheduler.reconcile(store, now)
                assertTrue(registry.all.isEmpty())
                store.updateLocalPlan(plan.copy(userId = 902, startTime = "12:00", endTime = "13:00"))
                scheduler.reconcile(store, now)
                assertEquals(3, registry.all.size)
                assertTrue(registry.all.keys.all { it.startsWith("$ownerB\n") })

                TokenStore.save(preferences, AuthSession("isolated-qa-token", 901, 901, signedIn = true), ConnectionStore.state.value)
                scheduler.invalidate()
                PlanAlarmScheduler(context).reconcile(store, now)
                assertEquals(3, registry.all.size)
                assertTrue(registry.all.keys.all { it.startsWith("$ownerA\n") })
                assertEquals("10:00", store.plan(plan.id)?.startTime)
                context.filesDir.resolve("alarm-identity-proof.json").writeText(
                    """{"realAlarmManager":true,"realSessionState":true,"networkLogin":false,"oldKeysRemoved":true,"ownerRestored":true}""", Charsets.UTF_8)
            } finally {
                scheduler.invalidate()
                TokenStore.restore(context.planLockPreferences)
                scheduler.reconcile(RecordStore(context))
                db.close()
                job.cancel()
                file.delete()
            }
        }
    }
}
