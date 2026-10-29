package com.planlock.app.record

import org.junit.Assert.*
import org.junit.Test

class PlanAlarmLatenessTest {
    @Test fun strictSixtySecondBoundaryForAllKinds() {
        PlanAlarmKind.entries.forEach { _ ->
            assertFalse(PlanAlarmLateness.isDeferred(1_000, 60_999))
            assertFalse(PlanAlarmLateness.isDeferred(1_000, 61_000))
            assertTrue(PlanAlarmLateness.isDeferred(1_000, 61_001))
        }
    }
    @Test fun lateAdvanceReminderOnlySurvivesBeforeStart() {
        assertTrue(PlanAlarmLateness.mayRemind(1_000, 61_001, 120_000, 180_000))
        assertFalse(PlanAlarmLateness.mayRemind(1_000, 120_000, 120_000, 180_000))
        assertFalse(PlanAlarmLateness.mayRemind(1_000, 180_000, 120_000, 180_000))
    }
    @Test fun delayedStartRetainsOriginalWindowRule() {
        assertFalse(PlanAlarmLateness.mayStart(99_999, 100_000, 200_000))
        assertTrue(PlanAlarmLateness.mayStart(100_000, 100_000, 200_000))
        assertTrue(PlanAlarmLateness.mayStart(199_999, 100_000, 200_000))
        assertFalse(PlanAlarmLateness.mayStart(200_000, 100_000, 200_000))
    }
    @Test fun deferralDisplayUsesElapsedWholeMinutes() {
        assertEquals(1, PlanAlarmLateness.delayedMinutes(60_001).toInt())
        assertEquals(2, PlanAlarmLateness.delayedMinutes(120_001).toInt())
        assertEquals(3, PlanAlarmLateness.delayedMinutes(180_000).toInt())
    }
}
