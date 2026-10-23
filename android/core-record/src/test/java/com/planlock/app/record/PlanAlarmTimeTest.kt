package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class PlanAlarmTimeTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(value: String) = requireNotNull(SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).apply { timeZone = zone }.parse(value)).time
    private fun plan(start: String = "10:00", end: String = "11:00", remind: Int? = 15, rule: String? = null) =
        PlanSnapshotEntity(-1, 0, "测试", "学习", start, end, rule, null, null, remind, null, 1, 0)
    @Test fun advanceRepeatsAndNullReminderStillKeepsExecutionBoundaries() {
        val p = plan()
        assertEquals(time("2026-10-20 09:45"), PlanAlarmTime.next(p, PlanAlarmKind.REMINDER, time("2026-10-20 09:00"), zone)?.triggerAt)
        assertEquals(time("2026-10-21 09:45"), PlanAlarmTime.next(p, PlanAlarmKind.REMINDER, time("2026-10-20 09:45"), zone)?.triggerAt)
        assertNull(PlanAlarmTime.next(plan(remind = null), PlanAlarmKind.REMINDER, time("2026-10-20 09:00"), zone))
        assertNotNull(PlanAlarmTime.next(plan(remind = null), PlanAlarmKind.END, time("2026-10-20 09:00"), zone))
    }
    @Test fun overnightEndRetainsPreviousStartDateAndEndedOccurrenceIsSkipped() {
        val p = plan("23:00", "01:00")
        val alarm = requireNotNull(PlanAlarmTime.next(p, PlanAlarmKind.END, time("2026-10-21 00:30"), zone))
        assertEquals("2026-10-20", alarm.date)
        assertEquals(time("2026-10-21 01:00"), alarm.triggerAt)
        assertEquals("2026-10-21", PlanAlarmTime.next(p.copy(endedOccurrenceDate = "2026-10-20"), PlanAlarmKind.END, time("2026-10-21 00:30"), zone)?.date)
    }
    @Test fun disabledMalformedWeeklyAndYearlyRulesDoNotBecomeDaily() {
        val at = time("2026-10-20 09:00")
        assertNull(PlanAlarmTime.next(plan().copy(status = 0), PlanAlarmKind.START, at, zone))
        assertNull(PlanAlarmTime.next(plan(rule = "broken"), PlanAlarmKind.START, at, zone))
        assertEquals("2026-10-26", PlanAlarmTime.next(plan(rule = "{\"type\":\"weekly\",\"weekdays\":[1]}"), PlanAlarmKind.START, at, zone)?.date)
        assertEquals("2027-01-01", PlanAlarmTime.next(plan(rule = "{\"type\":\"yearly\",\"month\":1,\"day\":1}"), PlanAlarmKind.START, at, zone)?.date)
    }
    @Test fun advanceAcrossDateAndDaylightSavingUsesCalendarDates() {
        assertEquals(time("2026-10-19 23:50"), PlanAlarmTime.next(plan("00:10", "01:00", 20), PlanAlarmKind.REMINDER, time("2026-10-19 22:00"), zone)?.triggerAt)
        val ny = TimeZone.getTimeZone("America/New_York")
        val alarm = PlanAlarmTime.next(plan("09:00", "10:00"), PlanAlarmKind.START, time("2026-03-07 23:00"), ny)!!
        val c = java.util.Calendar.getInstance(ny).apply { timeInMillis = alarm.triggerAt }
        assertEquals(9, c.get(java.util.Calendar.HOUR_OF_DAY))
    }
}
