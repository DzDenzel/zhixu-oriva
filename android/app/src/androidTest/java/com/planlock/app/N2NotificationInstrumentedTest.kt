package com.planlock.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.RecordStore
import com.planlock.app.record.PlanAlarmScheduler
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class N2NotificationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "com.planlock.qapack3a") }
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun clock(c: Calendar) = String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    private fun ongoing() = notifications.activeNotifications.any { it.id == 1 && it.tag == null }
    private suspend fun awaitState(stage: String = "state", check: () -> Boolean) {
        android.util.Log.i("OrivaN2Test", "await=$stage visible=${AppVisibility.foreground}")
        try { withTimeout(8_000) { while (!check()) delay(100) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$stage; visible=${AppVisibility.foreground}; notifications=" + notifications.activeNotifications.joinToString { "${it.id}:${it.tag}" }, error)
        }
    }
    private fun plan(id: Long, name: String, start: Calendar, end: Calendar, type: String = "学习") =
        PlanSnapshotEntity(id, 0, name, type, clock(start), clock(end), null, null, null, 1, null, 1, 0)
    private suspend fun requireNoOtherActivePlans(store: RecordStore) {
        val now = Calendar.getInstance()
        assertTrue("Use isolated QA without an existing active plan", store.plans().none {
            PlanOccurrence.isActive(it, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), OccurrenceDate.from(now))
        })
    }

    @Test fun activeSaveStartsOngoingAndManualEndRemovesItWithoutCompletion() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        requireNoOtherActivePlans(store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val id = -System.currentTimeMillis()
        try {
            awaitState("foreground-ready") { AppVisibility.foreground }
            awaitState("initial-idle") { !ongoing() }
            val start = Calendar.getInstance().apply { add(Calendar.MINUTE, -1) }
            val end = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
            AppSync.get(context).withLocalMutation { store.updateLocalPlan(plan(id, "N2 进行中检验", start, end)) }
            awaitState("active-ongoing") { ongoing() }
            assertEquals("正在按计划进行", notifications.activeNotifications.single { it.id == 1 && it.tag == null }
                .notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            AppSync.get(context).withLocalMutation { assertTrue(store.endOccurrence(id, System.currentTimeMillis(), false, "qa-test")) }
            awaitState("ended-no-ongoing") { !ongoing() }
            assertFalse(notifications.activeNotifications.any { it.id == id.hashCode() && it.tag?.startsWith("oriva-plan:") == true })
            assertEquals(1, store.executionHistory().count { it.planId == id && it.status == 2 })
        } finally {
            AppSync.get(context).withLocalMutation {
                store.finishOccurrence(id, System.currentTimeMillis())
                store.deletePlan(id)
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun prepareRealClockWalkthrough() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        requireNoOtherActivePlans(store)
        if (android.os.Build.VERSION.SDK_INT >= 33) check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (android.os.Build.VERSION.SDK_INT >= 31) check(context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
        val start = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 2) }
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, 2) }
        val id = -System.currentTimeMillis()
        AppSync.get(context).withLocalMutation {
            val typeKey = store.planTypes().firstOrNull { it.name == "N2练琴" }?.key ?: store.createPlanType("N2练琴", "hobby")
            store.updateLocalPlan(plan(id, "N2 后台实测", start, end, typeKey))
            PlanAlarmScheduler(context).reconcile(store)
        }
        context.filesDir.resolve("n2-times.json").writeText("""{"planId":$id,"reminder":${start.timeInMillis-60_000},"start":${start.timeInMillis},"end":${end.timeInMillis},"timeText":"${clock(start)} – ${clock(end)} · N2练琴"}""", Charsets.UTF_8)
    }

    @Test fun verifyRealClockCompletionAndCleanup() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        val id = org.json.JSONObject(context.filesDir.resolve("n2-times.json").readText(Charsets.UTF_8)).getLong("planId")
        val rows = store.executionHistory().filter { it.planId == id }
        assertEquals(listOf(0, 1), rows.map { it.status }.sortedBy { it })
        assertFalse(ongoing())
        context.filesDir.resolve("n2-record-proof.json").writeText("""{"startRows":1,"completedRows":1,"duplicateCompletion":false,"idleOngoing":false}""", Charsets.UTF_8)
        AppSync.get(context).withLocalMutation {
            val key = store.plan(id)?.type
            store.deletePlan(id)
            if (key?.startsWith("custom:") == true) store.deletePlanType(key)
            PlanAlarmScheduler(context).reconcile(store)
        }
    }
}
