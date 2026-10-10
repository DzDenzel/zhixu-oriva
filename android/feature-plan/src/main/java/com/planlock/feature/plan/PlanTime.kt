package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate

object PlanTime {
    /** Current occurrence only; the response plugin owns any shorter action duration. */
    fun activeUntilMillis(plan: PlanSnapshotEntity, clock: java.util.Calendar): Long {
        val minute = clock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + clock.get(java.util.Calendar.MINUTE)
        if (!isActive(plan, minute, OccurrenceDate.from(clock))) return 0
        val end = minutesOfDay(plan.endTime) ?: return 0
        return (clock.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, end / 60)
            set(java.util.Calendar.MINUTE, end % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= clock.timeInMillis) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }
    fun minutesOfDay(value: String?): Int? {
        val parts = value?.split(":") ?: return null
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun isActive(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): Boolean =
        PlanOccurrence.isActive(plan, nowMinutes, date)

    fun isWithinClockWindow(plan: PlanSnapshotEntity, nowMinutes: Int): Boolean {
        val start = minutesOfDay(plan.startTime) ?: return false
        val end = minutesOfDay(plan.endTime) ?: return false
        if (start == end) return false
        return if (end > start) nowMinutes in start until end else nowMinutes >= start || nowMinutes < end
    }

    fun progress(plan: PlanSnapshotEntity, nowMinutes: Int): Float {
        val start = minutesOfDay(plan.startTime) ?: return 0f
        val end = minutesOfDay(plan.endTime) ?: return 0f
        if (end == start) return 0f
        val duration = if (end > start) end - start else 24 * 60 - start + end
        if (duration <= 0) return 0f
        val elapsed = if (end > start) nowMinutes - start else if (nowMinutes >= start) nowMinutes - start else 24 * 60 - start + nowMinutes
        return (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    }

    fun remainingMinutes(plan: PlanSnapshotEntity, nowMinutes: Int): Int {
        val end = minutesOfDay(plan.endTime) ?: return 0
        val start = minutesOfDay(plan.startTime) ?: return 0
        if (end == start) return 0
        return if (end >= nowMinutes) end - nowMinutes else 24 * 60 - nowMinutes + end
    }

    fun format(value: String?): String = value?.take(5) ?: "--:--"
}
