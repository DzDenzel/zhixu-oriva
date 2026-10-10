package com.planlock.app.record

import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleReminderTimeTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test fun yearlyBirthdaySchedulesNextMatchingMonthAndDay() {
        val schedule = schedule("2020-03-05", "09:00", "{\"type\":\"yearly\",\"month\":3,\"day\":5}", 60)
        val now = millis(2029, 3, 4, 12, 0)
        assertEquals(millis(2029, 3, 5, 8, 0), ScheduleReminderTime.nextTrigger(schedule, now, zone))
    }

    @Test fun monthlyReminderSkipsElapsedOccurrence() {
        val schedule = schedule("2020-01-15", "10:00", "{\"type\":\"monthly\",\"day\":15}", 30)
        val now = millis(2029, 3, 15, 10, 0)
        assertEquals(millis(2029, 4, 15, 9, 30), ScheduleReminderTime.nextTrigger(schedule, now, zone))
    }

    @Test fun oneShotNeverRepeatsAfterItsTrigger() {
        val schedule = schedule("2029-03-05", "09:00", null, 15)
        assertNull(ScheduleReminderTime.nextTrigger(schedule, millis(2029, 3, 5, 9, 0), zone))
    }

    private fun schedule(date: String, time: String, rule: String?, advance: Int) = ScheduleSnapshotEntity(
        1, 1, "生日", date, time, date, "10:00", false, null, null, advance, rule, null, false, zone.id, 1, 0,
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis
}
