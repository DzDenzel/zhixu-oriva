package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class PlanAlarmKind { REMINDER, START, END }
data class PlanAlarm(val kind: PlanAlarmKind, val triggerAt: Long, val startAt: Long, val endAt: Long, val date: String)

/** Calendar arithmetic preserves occurrence dates across midnight, time zones and DST. */
object PlanAlarmTime {
    fun next(plan: PlanSnapshotEntity, kind: PlanAlarmKind, after: Long, zone: TimeZone = TimeZone.getDefault()): PlanAlarm? {
        if (plan.status != 1 || kind == PlanAlarmKind.REMINDER && plan.remindAdvanceMin == null) return null
        fun minutes(value: String?): Int? {
            val parts = value?.split(':') ?: return null
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
        }
        val startMinutes = minutes(plan.startTime) ?: return null
        val endMinutes = minutes(plan.endTime) ?: return null
        if (startMinutes == endMinutes) return null
        val advance = (plan.remindAdvanceMin ?: 0).toLong() * 60_000L
        if (advance < 0) return null
        val day = Calendar.getInstance(zone).apply {
            timeInMillis = after + if (kind == PlanAlarmKind.REMINDER) advance else 0
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (kind == PlanAlarmKind.END) add(Calendar.DAY_OF_MONTH, -1)
        }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
        repeat(372) {
            val date = format.format(day.time)
            if (PlanOccurrence.occursOn(plan, OccurrenceDate.from(day)) && plan.endedOccurrenceDate != date) {
                fun at(minutes: Int) = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60)
                }
                val start = at(startMinutes).timeInMillis
                val end = at(endMinutes).apply { if (endMinutes < startMinutes) add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                val trigger = when (kind) { PlanAlarmKind.REMINDER -> start - advance; PlanAlarmKind.START -> start; PlanAlarmKind.END -> end }
                if (trigger > after) return PlanAlarm(kind, trigger, start, end, date)
            }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }
}
