package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.OccurrenceDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTimeTest {
    @Test fun actionDeadlineUsesOccurrenceAndExactMinuteRatherThanFifteenMinutesBeyondEnd() {
        val clock = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, 9, 8, 23, 59, 40)
        }
        assertEquals(20_000L, PlanTime.activeUntilMillis(plan("23:00", "00:00"), clock) - clock.timeInMillis)
        assertEquals(0L, PlanTime.activeUntilMillis(plan("10:00", "11:00"), clock))
        assertEquals(0L, PlanTime.activeUntilMillis(plan("23:00", "00:00").copy(status = 0), clock))
    }
    private fun plan(start: String, end: String) = PlanSnapshotEntity(
        id = 1, userId = 0, name = "test", type = "学习", startTime = start, endTime = end,
        repeatRule = null, color = null, icon = null, remindAdvanceMin = 15,
        remindMethods = null, status = 1, version = 0
    )

    @Test
    fun crossDayProgressUsesSameTimeRangeAsActiveState() {
        val value = plan("23:00:00", "01:00:00")
        assertTrue(PlanTime.isActive(value, 0, OccurrenceDate(2026, 9, 28, 1)))
        assertEquals(.5f, PlanTime.progress(value, 0), .001f)
        assertEquals(60, PlanTime.remainingMinutes(value, 0))
    }
}
