package com.planlock.app

import android.app.AlarmManager
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.PlanTimingPreferences
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class PunctualPlanAlarmInstrumentedTest {
    /** Explicit fixture cleanup, usable while the test phone is locked; never touches other QA plans. */
    @Test fun cleanupExplicitUiTestPlans() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = InstrumentationRegistry.getArguments().getString("cleanupPlanIds")
        org.junit.Assume.assumeNotNull(raw)
        val ids = requireNotNull(raw).split(',').map(String::toLong).toSet()
        require(ids.size in 1..2)
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        com.planlock.app.api.TokenStore.restore(context.planLockPreferences)
        val store = RecordStore(context)
        AppSync.get(context).withLocalMutation {
            val before = store.plans()
            val selected = before.filter { it.id in ids }
            require(selected.all { it.name in setOf("PHONE-CLOCK-15M", "PHONE-CLOCK-5M") })
            val preserved = before.filter { it.id !in ids }.map { it.id }.toSet()
            selected.forEach { store.deletePlan(it.id) }
            PlanAlarmScheduler(context).reconcile(store)
            assertEquals(preserved, store.plans().map { it.id }.toSet())
            context.filesDir.resolve("punctual-cleanup-proof.json").writeText(
                """{"removed":${selected.size},"unrelatedPlansPreserved":true,"viaRecordStore":true}""", Charsets.UTF_8)
        }
    }

    @Test fun systemClockShowIntentOpensExistingPlanDetails() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        com.planlock.app.api.TokenStore.restore(context.planLockPreferences)
        val clock = context.getSystemService(AlarmManager::class.java).nextAlarmClock
        org.junit.Assume.assumeTrue("An existing QA plan clock is required", clock?.showIntent?.creatorPackage == context.packageName)
        val selectedClock = requireNotNull(clock)
        val now = System.currentTimeMillis()
        val plan = RecordStore(context).plans().first { plan ->
            listOf(PlanAlarmKind.START, PlanAlarmKind.REMINDER).any {
                PlanAlarmTime.next(plan, it, now)?.triggerAt == selectedClock.triggerTime
            }
        }
        selectedClock.showIntent.send()
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        var found = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val labels = mutableListOf<String>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                node.text?.toString()?.let(labels::add)
                node.contentDescription?.toString()?.let(labels::add)
                for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it) }
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
            if ("计划详情" in labels && plan.name in labels) { found = true; break }
            kotlinx.coroutines.delay(200)
        }
        assertTrue("The OS-provided showIntent did not open its plan details", found)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.filesDir.resolve("punctual-show-details.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        context.filesDir.resolve("punctual-show-proof.json").writeText(
            """{"osProvidedShowIntent":true,"planId":${plan.id},"detailsTitleObserved":true,"correctPlanObserved":true}""", Charsets.UTF_8)
    }

    @Test fun toggleReplacesRealClockAlarmsAndCancellationRemovesClockAndShowIntent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        val manager = context.getSystemService(AlarmManager::class.java)
        check(manager.canScheduleExactAlarms())
        val original = context.planLockPreferences.data.first()[PlanLockPreferenceKeys.punctualPlanStart]
        val database = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        val store = RecordStore(database)
        val scheduler = PlanAlarmScheduler(context)
        val registry = context.getSharedPreferences("scoped_plan_alarms", 0)
        val start = Calendar.getInstance().apply { add(Calendar.MINUTE, 20); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, 10) }
        fun clock(c: Calendar) = String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val plan = PlanSnapshotEntity(-219132, 0, "Punctual isolated QA", "study", clock(start), clock(end), null, null, null, 15, null, 1, 0)
        fun dump(stage: String) {
            val raw = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))
                .bufferedReader().use { it.readText() }
            val lines = raw.lines(); val selected = sortedSetOf<Int>()
            lines.forEachIndexed { i, line -> if (context.packageName in line) selected.addAll(maxOf(0, i-1)..minOf(lines.lastIndex, i+7)) }
            context.filesDir.resolve("punctual-$stage-alarms.txt").writeText(selected.joinToString("\n") { lines[it] }, Charsets.UTF_8)
        }
        AppSync.get(context).withLocalMutation {
            try {
                store.updateLocalPlan(plan)
                PlanTimingPreferences.setEnabled(context.planLockPreferences, true)
                scheduler.reconcile(store)
                assertEquals(3, registry.all.size)
                assertEquals(2, registry.all.values.count { it.toString().endsWith(":ALARM_CLOCK") })
                assertEquals(1, registry.all.values.count { it.toString().endsWith(":EXACT") })
                val show = requireNotNull(manager.nextAlarmClock).showIntent
                assertEquals(context.packageName, show.creatorPackage)
                assertEquals(start.timeInMillis - 15 * 60_000, requireNotNull(manager.nextAlarmClock).triggerTime)
                dump("enabled")

                PlanTimingPreferences.setEnabled(context.planLockPreferences, false)
                PlanAlarmScheduler(context).reconcile(store)
                assertTrue(registry.all.values.all { it.toString().endsWith(":EXACT") })
                assertFalse(manager.nextAlarmClock?.showIntent?.creatorPackage == context.packageName)
                try { show.send(); fail("Disabled clock retained its showIntent") } catch (_: android.app.PendingIntent.CanceledException) { }
                dump("disabled")

                PlanTimingPreferences.setEnabled(context.planLockPreferences, true)
                scheduler.reconcile(store)
                assertEquals(context.packageName, manager.nextAlarmClock?.showIntent?.creatorPackage)
                val restoredShow = requireNotNull(manager.nextAlarmClock).showIntent
                store.deletePlan(plan.id)
                scheduler.reconcile(store)
                assertTrue(registry.all.isEmpty())
                assertFalse(manager.nextAlarmClock?.showIntent?.creatorPackage == context.packageName)
                try { restoredShow.send(); fail("Deleted plan retained its showIntent") } catch (_: android.app.PendingIntent.CanceledException) { }
                context.filesDir.resolve("punctual-toggle-proof.json").writeText(
                    """{"realAlarmManager":true,"clockStartAndReminder":true,"ordinaryEnd":true,"offRemovesClock":true,"onRestoresClock":true,"deleteClearsShowIntent":true}""", Charsets.UTF_8)
            } finally {
                scheduler.invalidate()
                context.planLockPreferences.edit { if (original == null) it.remove(PlanLockPreferenceKeys.punctualPlanStart) else it[PlanLockPreferenceKeys.punctualPlanStart] = original }
                scheduler.reconcile(RecordStore(context))
                database.close()
            }
        }
    }
}
