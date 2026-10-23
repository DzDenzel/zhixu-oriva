package com.planlock.feature.plan

import com.planlock.app.record.entity.ExecutionHistoryEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.design.PlanVisualState
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlanOccurrencePresentationTest {
    private val plan = PlanSnapshotEntity(7, 1, "晨读", "study", "06:00", "07:00", null, null, null, 0, null, 1, 0, scope = "owner-a")
    private fun clock(day: Int = 21, hour: Int = 8, minute: Int = 0) = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00")).apply {
        clear(); set(2026, 9, day, hour, minute)
    }
    private fun end(status: Int = 1, date: String = "2026-10-21", at: String = "2026-10-21T07:00:00") =
        ExecutionHistoryEntity(plan.scope, "terminal", "execution", planId = plan.id, planDate = date, status = status, endedAt = at)

    @Test fun actualNaturalTerminalIsTodayDone() {
        assertEquals(PlanVisualState.TODAY_DONE, plan.visualState(clock(), listOf(end())))
        assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock()))
    }
    @Test fun earlyEndIsDistinctAndPauseStillWins() {
        val early = end(2, at = "2026-10-21T06:30:00")
        assertEquals(PlanVisualState.TODAY_ENDED, plan.visualState(clock(), listOf(early)))
        assertEquals(PlanVisualState.PAUSED, plan.copy(status = 0).visualState(clock(), listOf(end())))
        assertEquals(PlanVisualState.TODAY_ENDED, plan.copy(endedOccurrenceDate = "2026-10-21").visualState(clock()))
    }
    @Test fun oldOtherOwnerOtherPlanAndNonExecutionRowsCannotCompleteToday() {
        for (row in listOf(end(date = "2026-10-20"), end().copy(scope = "owner-b"), end().copy(planId = 8), end().copy(type = "unlock"))) {
            assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock(), listOf(row)))
        }
    }
    @Test fun unfinishedFutureAndInvalidTerminalsNeverPretendToBeCompletion() {
        for (row in listOf(end(0), end().copy(endedAt = null), end(at = "bad"), end(at = "2026-02-30T07:00:00"), end(at = "2026-10-21T09:00:00"))) {
            assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock(), listOf(row)))
        }
    }
    @Test fun beforeStartNonRepeatDayAndRescheduledStartRemainUpcoming() {
        assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock(hour = 5), listOf(end())))
        assertEquals(PlanVisualState.UPCOMING, plan.copy(startTime = "09:00", endTime = "10:00").visualState(clock(hour = 11), listOf(end())))
        val monday = plan.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1]}")
        assertEquals(PlanVisualState.UPCOMING, monday.visualState(clock(), listOf(end())))
    }
    @Test fun latestActualTerminalWinsAndTiePrefersEarlyEnd() {
        val early = end(2, at = "2026-10-21T06:30:00")
        assertEquals(PlanVisualState.TODAY_DONE, plan.visualState(clock(), listOf(end(), early)))
        assertEquals(PlanVisualState.TODAY_ENDED, plan.visualState(clock(), listOf(end(), end(2))))
        assertEquals(PlanVisualState.TODAY_ENDED, plan.visualState(clock(), listOf(end(2), end())))
    }
    @Test fun dayChangeDropsYesterdayAndNextStartIsExecuting() {
        assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock(day = 22), listOf(end())))
        assertEquals(PlanVisualState.EXECUTING, plan.visualState(clock(day = 22, hour = 6, minute = 30), listOf(end())))
    }
    @Test fun overnightCompletionUsesStartDayAtAndAfterEndUntilNextStart() {
        val overnight = plan.copy(startTime = "23:00", endTime = "06:00")
        val record = end(date = "2026-10-20", at = "2026-10-21T06:00:00")
        assertEquals(PlanVisualState.TODAY_DONE, overnight.visualState(clock(hour = 6), listOf(record)))
        assertEquals(PlanVisualState.TODAY_DONE, overnight.visualState(clock(hour = 8), listOf(record)))
        assertEquals(PlanVisualState.EXECUTING, overnight.visualState(clock(hour = 23), listOf(record)))
        assertEquals(PlanVisualState.TODAY_ENDED, overnight.copy(endedOccurrenceDate = "2026-10-20").visualState(clock(hour = 8)))
    }
    @Test fun timezoneAndFractionalTerminalsKeepTheirActualInstant() {
        assertEquals(PlanVisualState.TODAY_DONE, plan.visualState(clock(), listOf(end(at = "2026-10-20T23:00:00Z"))))
        assertEquals(PlanVisualState.TODAY_DONE, plan.visualState(clock(), listOf(end(at = "2026-10-21T07:00:00.123456+08:00"))))
        assertEquals(PlanVisualState.UPCOMING, plan.visualState(clock(), listOf(end(at = "2026-10-21T07:00:00Z"))))
    }
    @Test fun malformedRulesAndZeroLengthWindowsCannotProduceResults() {
        assertEquals(PlanVisualState.UPCOMING, plan.copy(repeatRule = "bad").visualState(clock(), listOf(end())))
        assertEquals(PlanVisualState.UPCOMING, plan.copy(endTime = "06:00").visualState(clock(), listOf(end())))
    }
    @Test fun boundedReadIncludesPreviousStartDateAcrossMonthAndYear() {
        val jan1 = clock().apply { set(2027, 0, 1) }
        assertEquals("2026-12-31" to "2027-01-01", executionHistoryDates(jan1))
    }
}
