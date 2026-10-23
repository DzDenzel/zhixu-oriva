package com.planlock.app

import android.app.NotificationManager
import android.content.Intent
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.edit
import com.planlock.app.api.*
import com.planlock.app.api.dto.ViolationRecordRequest
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.FeedbackKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class FinishPackInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName.startsWith("com.planlock.qa")) }
    private fun at(hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun plan(id: Long) = PlanSnapshotEntity(id, 0, "QA 提醒接缝", "学习", "10:00", "11:00", null, null, null, 15, null, 1, 0)

    @Test fun alarmRegistrationMovesWithCanonicalIdAndDisappearsOnPauseAndDelete() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        AppSync.get(context).withLocalMutation {
            val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
            val store = RecordStore(db, { TokenStore.state.value.dataScope })
            val scheduler = PlanAlarmScheduler(context)
            val registry = context.getSharedPreferences("scoped_plan_alarms", 0)
            val future = at(8) + 86_400_000L
            try {
                store.updateLocalPlan(plan(-117_001))
                scheduler.reconcile(store, future)
                assertEquals(3, registry.all.keys.count { it.contains("\n-117001\n") })
                store.replacePlanId(-117_001, 117_001)
                scheduler.reconcile(store, future)
                assertFalse(registry.all.keys.any { it.contains("\n-117001\n") })
                assertEquals(3, registry.all.keys.count { it.contains("\n117001\n") })
                store.plan(117_001)!!.let { store.updateLocalPlan(it.copy(status = 0)) }
                scheduler.reconcile(store, future)
                assertTrue(registry.all.isEmpty())
                store.plan(117_001)!!.let { store.updateLocalPlan(it.copy(status = 1, remindAdvanceMin = null)) }
                scheduler.reconcile(store, future)
                assertEquals(2, registry.all.size)
                scheduler.invalidate()
                scheduler.reconcile(store, future)
                assertEquals(2, registry.all.size)
                store.deletePlan(117_001)
                scheduler.reconcile(store, future)
                assertTrue(registry.all.isEmpty())
            } finally { db.close(); scheduler.reconcile(RecordStore(context)) }
        }
    }

    @Test fun completionIsOnceAndHostObservationNeverEntersTheOutbox() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val store = RecordStore(db, { TokenStore.state.value.dataScope })
        try {
            store.updateLocalPlan(plan(-117_002))
            assertTrue(store.beginOccurrence(-117_002, at(10, 10)))
            assertTrue(PlanCompletion.finish(context, store, store.plan(-117_002)!!, at(11)))
            assertFalse(PlanCompletion.finish(context, store, store.plan(-117_002)!!, at(11)))
            assertEquals(1, store.executionHistory().count { it.status == 1 })
            store.enqueueViolation(ViolationRecordRequest(-117_002, "2026-10-20T10:00:00", packageName = context.packageName, clientId = "qa-host"))
            assertFalse(store.pending().any { it.type == "violation" })
            context.filesDir.resolve("finish-pack-room.json").writeText("""{"completionOnce":true,"hostExcluded":true}""", Charsets.UTF_8)
        } finally {
            context.getSystemService(NotificationManager::class.java).cancel("oriva-plan:${store.currentScope()}", (-117_002L).hashCode())
            db.close()
        }
    }

    @Test fun foregroundCompletionUsesOnlyInAppFeedbackAndManualEndStaysQuiet() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TokenStore.restore(context.planLockPreferences)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val store = RecordStore(db, { TokenStore.state.value.dataScope })
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = -117_005L
        try {
            assertTrue("Actual MainActivity must be visible", AppVisibility.foreground)
            store.updateLocalPlan(plan(id))
            assertTrue(store.beginOccurrence(id, at(10)))
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { ExecutionFeedback.events.first { it.planId == id && it.kind == FeedbackKind.COMPLETED } }
            }
            assertTrue(PlanCompletion.finish(context, store, store.plan(id)!!, at(11)))
            assertEquals(id, event.await().planId)
            assertFalse(manager.activeNotifications.any { it.id == id.hashCode() && it.tag?.startsWith("oriva-plan:") == true })
            val manualId = id - 1
            store.updateLocalPlan(plan(manualId))
            assertTrue(store.beginOccurrence(manualId, at(10)))
            assertTrue(store.endOccurrence(manualId, at(10, 30), false, "qa-test"))
            assertFalse(PlanCompletion.finish(context, store, store.plan(manualId)!!, at(11)))
            assertFalse(manager.activeNotifications.any { it.id == manualId.hashCode() && it.tag?.startsWith("oriva-plan:") == true })
            assertEquals(1, store.executionHistory().count { it.planId == manualId && it.status == 2 })
        } finally {
            db.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** Runs against the retained v2.1 QA binary as well as the candidate; uses only its stable APIs. */
    @Test fun prepareLegacyUpgradeScenario() = runBlocking {
        val explicit = InstrumentationRegistry.getArguments().getString("upgrade_case") == "explicit"
        context.planLockPreferences.edit { it.clear() }
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        store.forScope("local\nlocal").saveLocalPlan(plan(-118_001).copy(name = "升级保留的本机计划", status = 0))
        if (explicit) TokenStore.changeConnection(context.planLockPreferences,
            ConnectionConfig(instanceLabel = "手动导入的后端", apiBaseUrl = "https://upgrade.example.invalid/api/v1/", pluginsCatalogUrl = "https://upgrade.example.invalid/plugins/"))
        context.filesDir.resolve("upgrade-case.txt").writeText(if (explicit) "explicit" else "implicit", Charsets.UTF_8)
    }

    @Test fun verifyLegacyUpgradeScenario() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val explicit = context.filesDir.resolve("upgrade-case.txt").readText(Charsets.UTF_8) == "explicit"
        if (explicit) assertEquals("https://upgrade.example.invalid/api/v1/", ConnectionStore.state.value.config?.apiBaseUrl)
        else { assertNull(ConnectionStore.state.value.config); assertTrue(ConnectionStore.needsChoice(context.planLockPreferences)) }
        assertEquals("升级保留的本机计划", RecordStore(context).forScope("local\nlocal").plan(-118_001)?.name)
        context.filesDir.resolve("upgrade-${if (explicit) "explicit" else "implicit"}-proof.json")
            .writeText("""{"explicit":$explicit,"dataPreserved":true,"connectionPolicyCorrect":true}""", Charsets.UTF_8)
    }

    /** Explicit preparation for the second CRUD group; does not require a backend or system grant. */
    @Test fun prepareCachedOfflineCrudScenario() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val connection = ConnectionConfig(instanceLabel = "QA 离线缓存夹具", apiBaseUrl = "http://10.0.2.2:18089/api/v1/",
            pluginsCatalogUrl = "http://10.0.2.2:18089/api/v1/plugins/catalog/", lanAllowed = true)
        AppSync.get(context).withSessionChange {
            TokenStore.changeConnection(context.planLockPreferences, connection)
            TokenStore.save(context.planLockPreferences, AuthSession("qa-offline-fixture-only", 117_200, 117_200, signedIn = true))
        }
        context.filesDir.resolve("finish-offline-scope.json").writeText("""{"cachedSessionFixture":true,"backendReachable":false}""", Charsets.UTF_8)
    }

    /** Explicit manual scenario preparation; the owner grants system permissions before this method runs. */
    @Test fun prepareBackgroundReminderWalkthrough() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        check(com.planlock.app.security.CapabilityAccess(context).check(com.planlock.app.security.Capability.NOTIFICATIONS)) { "Owner must enable QA notification permission first" }
        check(com.planlock.app.security.CapabilityAccess(context).check(com.planlock.app.security.Capability.EXACT_ALARM)) { "Owner must enable QA exact alarms first" }
        TokenStore.changeConnection(context.planLockPreferences, null)
        val start = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 2) }
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, 2) }
        fun clock(c: Calendar) = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val store = RecordStore(context)
        val row = plan(-117_003).copy(name = "后台提醒实测", startTime = clock(start), endTime = clock(end), remindAdvanceMin = 1)
        AppSync.get(context).withLocalMutation { store.updateLocalPlan(row); PlanAlarmScheduler(context).reconcile(store) }
        context.filesDir.resolve("finish-background-times.json").writeText("""{"planId":-117003,"reminder":${start.timeInMillis - 60_000},"start":${start.timeInMillis},"end":${end.timeInMillis}}""", Charsets.UTF_8)
    }
}
