package com.planlock.feature.plan

import com.planlock.app.record.DayTimeline
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class EndedPlanPresentationTest {
    private val now = Calendar.getInstance()
    private val date = OccurrenceDate.from(now)
    private val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    private val plan = PlanSnapshotEntity(109, 0, "今天已结束", "study", "00:00", "23:59", null, null, null, 0, null, 1, 0)
    private val ended = plan.copy(endedOccurrenceDate = PlanOccurrence.occurrenceDate(plan, minutes, date))

    @Test fun `resumed and today only plans both display today ended`() {
        assertEquals("今天已结束", ended.toCardData().state.label)
        val row = DayTimeline.build(ended.endedOccurrenceDate!!, listOf(ended), emptyList(), minutes).single()
        assertEquals("计划 · 今天已结束", todayRowStatus(row))
        assertFalse(needsEndChoice(ended, ProtectedPlanAction.PAUSE, minutes, date))
        assertFalse(PlanOccurrence.canToggleExecution(ended, minutes, date))
        assertTrue(PlanOccurrence.canToggleExecution(ended.copy(status = 0), minutes, date))
    }

    @Test fun `tomorrow and cross midnight use the occurrence start date`() {
        fun clock(day: Int, hour: Int) = Calendar.getInstance().apply { clear(); set(2026, 9, day, hour, 30) }
        val overnight = plan.copy(startTime = "23:00", endTime = "06:00", endedOccurrenceDate = "2026-10-18")
        assertEquals("今天已结束", overnight.visualState(clock(19, 1)).label)
        assertFalse(PlanOccurrence.canToggleExecution(overnight, 90, OccurrenceDate.from(clock(19, 1))))
        assertEquals("进行中", overnight.visualState(clock(19, 23)).label)
        assertTrue(needsEndChoice(overnight, ProtectedPlanAction.PAUSE, 1410, OccurrenceDate.from(clock(19, 23))))
        val daytime = plan.copy(startTime = "09:00", endTime = "11:00", endedOccurrenceDate = "2026-10-18")
        assertEquals("今天已结束", daytime.visualState(clock(18, 10)).label)
        assertEquals("进行中", daytime.visualState(clock(19, 10)).label)
        assertEquals("已暂停", daytime.copy(status = 0).visualState(clock(18, 10)).label)
    }
}
