package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NextRepeatOccurrenceTest {
    private val plan = PlanSnapshotEntity(7, 1, "晨读", "study", "06:00", "07:00", null, null, null, 0, null, 1, 0)
    private fun clock(year: Int = 2026, month: Int = 10, day: Int = 21, hour: Int = 8) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear(); set(year, month - 1, day, hour, 0)
    }
    private fun date(c: Calendar?) = requireNotNull(c).let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.MONTH)+1}-${it.get(Calendar.DAY_OF_MONTH)} ${it.get(Calendar.HOUR_OF_DAY)}:${it.get(Calendar.MINUTE)}" }
    @Test fun dailyAfterCompletionIsTomorrowAndInputIsUntouched() {
        val now = clock(); val before = now.timeInMillis
        val next = nextRepeatOccurrence(plan, now)!!
        assertEquals("2026-10-22 6:0", date(next))
        assertEquals(before, now.timeInMillis)
        assertNotSame(now, next)
        assertEquals(now.timeZone, next.timeZone)
    }
    @Test fun beforeStartCanReturnTodayButExactStartMustAdvance() {
        assertEquals("2026-10-21 6:0", date(nextRepeatOccurrence(plan, clock(hour = 5))))
        assertEquals("2026-10-22 6:0", date(nextRepeatOccurrence(plan, clock(hour = 6))))
    }
    @Test fun weeklyMonthlyAndYearlyRespectSharedRepeatRules() {
        assertEquals("2026-10-26 6:0", date(nextRepeatOccurrence(plan.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1]}"), clock())))
        assertEquals("2026-12-31 6:0", date(nextRepeatOccurrence(plan.copy(repeatRule = "{\"type\":\"monthly\",\"day\":31}"), clock(month = 11))))
        assertEquals("2027-1-1 6:0", date(nextRepeatOccurrence(plan.copy(repeatRule = "{\"type\":\"yearly\",\"month\":1,\"day\":1}"), clock())))
    }
    @Test fun leapDaySearchCrossesTheNonLeapCentury() {
        assertEquals("2104-2-29 6:0", date(nextRepeatOccurrence(plan.copy(repeatRule = "{\"type\":\"yearly\",\"month\":2,\"day\":29}"), clock(year = 2096, month = 3, day = 1))))
    }
    @Test fun noValidNextTimeIsNull() {
        for (bad in listOf(plan.copy(status = 0), plan.copy(repeatRule = "bad"), plan.copy(startTime = "bad"), plan.copy(endTime = "06:00"), plan.copy(repeatRule = "{\"type\":\"yearly\",\"month\":2,\"day\":30}"))) {
            assertNull(nextRepeatOccurrence(bad, clock()))
        }
    }
    @Test fun overnightNextStartIsLaterTodayAndCalendarDateIsNotShiftedByDuration() {
        assertEquals("2026-10-21 23:0", date(nextRepeatOccurrence(plan.copy(startTime = "23:00", endTime = "06:00"), clock())))
    }
    @Test fun localClockIsPreservedAcrossDstWithNoInputMutation() {
        val now = clock(year = 2026, month = 3, day = 7, hour = 8).apply { timeZone = TimeZone.getTimeZone("America/New_York"); clear(); set(2026, 2, 7, 8, 0) }
        val before = now.timeInMillis
        val next = nextRepeatOccurrence(plan, now)!!
        assertEquals("2026-3-8 6:0", date(next))
        assertEquals(21L, (next.timeInMillis - before) / 3_600_000)
        assertEquals(before, now.timeInMillis)
    }
}
