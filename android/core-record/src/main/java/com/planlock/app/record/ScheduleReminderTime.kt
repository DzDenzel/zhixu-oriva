package com.planlock.app.record

import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Pure next-trigger calculation. It shares [PlanOccurrence] and never enters execution records. */
object ScheduleReminderTime {
    fun nextTrigger(
        schedule: ScheduleSnapshotEntity,
        afterMillis: Long,
        fallbackZone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (schedule.status != 1 || schedule.remindAdvanceMin == null) return null
        val zone = schedule.tzId?.let(TimeZone::getTimeZone) ?: fallbackZone
        val firstDate = parseDate(schedule.startDate, zone) ?: return null
        val endDate = parseDate(schedule.endDate, zone) ?: firstDate
        val search = Calendar.getInstance(zone).apply {
            timeInMillis = maxOf(afterMillis, firstDate.timeInMillis)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(370) {
            val dateKey = DATE_FORMAT.get()!!.apply { timeZone = zone }.format(search.time)
            val date = OccurrenceDate.from(search)
            val inRange = dateKey >= schedule.startDate && (schedule.repeatRule?.isNotBlank() == true || dateKey <= schedule.endDate)
            if (inRange && (schedule.repeatRule.isNullOrBlank() || PlanOccurrence.occursOn(schedule.repeatRule, date))) {
                val event = (search.clone() as Calendar).apply {
                    if (schedule.allDay) {
                        set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                    } else {
                        val parts = schedule.startTime?.split(':') ?: return null
                        set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: return null)
                        set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: return null)
                    }
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val trigger = event.timeInMillis - schedule.remindAdvanceMin * 60_000L
                if (trigger > afterMillis) return trigger
            }
            search.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    private fun parseDate(value: String, zone: TimeZone): Calendar? = runCatching {
        val format = DATE_FORMAT.get()!!.apply { timeZone = zone; isLenient = false }
        Calendar.getInstance(zone).apply { time = format.parse(value) ?: return null }
    }.getOrNull()

    private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
