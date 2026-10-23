package com.planlock.app.dnd

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class DndDecisionTest {
    @Test
    fun api35EndingDisablesOurRuleEvenWhenAnotherRuleAlreadyRequiredAlarms() {
        assertEquals(DndAction.Restore(NotificationManager.INTERRUPTION_FILTER_ALL),
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS,
                currentFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS, api35Plus = true)))
    }

    @Test
    fun api35EndingReleasesOurRuleWhenUserChangedTheCombinedFilter() {
        assertEquals(DndAction.Restore(NotificationManager.INTERRUPTION_FILTER_ALL),
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                currentFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, api35Plus = true)))
    }

    @Test
    fun recordedPlanKeepsDndWhenTheSamePlanStillNeedsIt() {
        assertEquals(
            DndAction.Keep,
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL, currentFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS, neededPlanId = 7))
        )
    }

    @Test
    fun recordedPlanReappliesDndWhenTheSystemRevertedToPreviousFilter() {
        assertEquals(
            DndAction.Enable,
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL, currentFilter = NotificationManager.INTERRUPTION_FILTER_ALL, neededPlanId = 7))
        )
    }

    @Test
    fun endingOurDndRestoresThePreviousFilterOnlyWhenStillOnOurFilter() {
        assertEquals(
            DndAction.Restore(NotificationManager.INTERRUPTION_FILTER_PRIORITY),
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, currentFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS, neededPlanId = null))
        )
    }

    @Test
    fun userChangedDndIsNotOverwrittenWhenThePlanEnds() {
        assertEquals(
            DndAction.Clear,
            decideDndAction(DndState(recordedPlanId = 7, previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL, currentFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, neededPlanId = null))
        )
    }

    @Test
    fun api35WithoutARecordAttemptsCleanupWhenNoPlanNeedsDnd() {
        assertEquals(
            DndAction.Clear,
            decideDndAction(DndState(neededPlanId = null, api35Plus = true))
        )
    }

    @Test
    fun missingRecordForAnActiveDndPlanStartsDnd() {
        assertEquals(
            DndAction.Enable,
            decideDndAction(DndState(neededPlanId = 9))
        )
    }

    @Test
    fun missingPolicyAccessWithAnActivePlanReturnsPermissionMissing() {
        assertEquals(
            DndAction.PermissionMissing,
            decideDndAction(DndState(neededPlanId = 9, hasPolicyAccess = false))
        )
    }

    @Test
    fun api35AuthorizedFirstCleanupDoesNotRequireRuleDetection() {
        assertEquals(
            DndAction.Clear,
            decideDndAction(DndState(api35Plus = true, hasPolicyAccess = true, residualCleanupAttempted = false))
        )
    }

    @Test
    fun api35CleanupDoesNotRepeatAfterTheFirstAttempt() {
        assertEquals(
            DndAction.Noop,
            decideDndAction(DndState(api35Plus = true, hasPolicyAccess = true, residualCleanupAttempted = true))
        )
    }
}
