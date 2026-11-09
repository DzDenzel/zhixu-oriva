package com.planlock.app

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.dnd.DndController
import com.planlock.app.dnd.DndResult
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.RecordStore
import com.planlock.app.sync.AppSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * BUG-142: a plan's DND must not outlive the plan when the process that applied it died (FGS timeout crash,
 * kill) and no END alarm arrived. Uses the real platform rule; needs no plan and no UI.
 */
class QuietResidueInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var hadPolicyAccess = false

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun awaitFilter(expected: Int): Int {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (manager.currentInterruptionFilter != expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        return manager.currentInterruptionFilter
    }

    @Before fun grantPolicyAccess() {
        hadPolicyAccess = manager.isNotificationPolicyAccessGranted
        if (!hadPolicyAccess) shell("cmd notification allow_dnd ${context.packageName}")
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (!manager.isNotificationPolicyAccessGranted && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assumeTrue("Notification policy access unavailable", manager.isNotificationPolicyAccessGranted)
        // The test runs in the app process, whose start-up reconcile releases any DND not owned by a running plan
        // (the BUG-142 fix itself). Let that pass finish so it cannot race the fake plan's record below.
        runBlocking { AppSync.get(context).withLocalMutation { ExecutionService.reconcile(context) } }
        SystemClock.sleep(1_500)
        assumeTrue("User DND must be off before the test", manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @After fun restore() = runBlocking {
        DndController(context).reconcile(null)
        if (!hadPolicyAccess) shell("cmd notification disallow_dnd ${context.packageName}")
    }

    @Test fun residualPlanDndIsReleasedOnTheNextOpportunityWhenNoPlanIsRunning() = runBlocking {
        TokenStore.restore(context.planLockPreferences)
        val clock = Calendar.getInstance()
        val minute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
        assumeTrue("A real plan is active on this device", RecordStore(context).plans()
            .none { PlanOccurrence.isActive(it, minute, OccurrenceDate.from(clock)) })

        // The plan's process applied DND, then died without cleanup (the 2026-11-04 PJD110 timeline).
        assertEquals(DndResult.Applied, DndController(context).reconcile(RESIDUAL_PLAN_ID))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS))

        // Opening the app / any alarm / boot reaches this with no service running. The old helper returned
        // early here, leaving the rule active indefinitely.
        AppSync.get(context).withLocalMutation { ExecutionService.reconcile(context) }

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertNull(context.planLockPreferences.data.first()[PlanLockPreferenceKeys.dndPlanId])
    }

    @Test fun userTurningDndOffIsRespectedAndTheNextOccurrenceTurnsItOnAgain() = runBlocking {
        val dnd = DndController(context)
        assertEquals(DndResult.Applied, dnd.reconcile(RESIDUAL_PLAN_ID))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS))

        // Quick-settings "off" snoozes our still-active rule. `set_dnd off` reproduces that on API 36 (ColorOS
        // PJD110, 2026-11-04) but leaves an app's implicit rule active on API 37 images: skip there, the decision
        // itself is covered by DndDecisionTest.userTurningOurAppliedDndOffIsRespectedForTheOccurrence.
        shell("cmd notification set_dnd off")
        assumeTrue("Shell cannot deactivate an app rule on this image",
            awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALL) == NotificationManager.INTERRUPTION_FILTER_ALL)
        // The old decision re-enabled DND on the next 15-second evaluation.
        assertEquals(DndResult.Kept, dnd.reconcile(RESIDUAL_PLAN_ID))
        SystemClock.sleep(300)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, manager.currentInterruptionFilter)

        // The occurrence ends; the next one must get DND again despite the earlier snooze.
        dnd.reconcile(null)
        assertEquals(DndResult.Applied, dnd.reconcile(RESIDUAL_PLAN_ID))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, awaitFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    private companion object { const val RESIDUAL_PLAN_ID = -142L }
}
