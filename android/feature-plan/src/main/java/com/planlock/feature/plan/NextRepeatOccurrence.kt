package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.entity.PlanSnapshotEntity
import java.util.Calendar

/** Next enabled start, strictly after now, in the caller's timezone; no UI text or writes. */
internal fun nextRepeatOccurrence(plan: PlanSnapshotEntity, now: Calendar): Calendar? {
    if (plan.status != 1 || !PlanOccurrence.isSupportedRule(plan.repeatRule)) return null
    val start = PlanTime.minutesOfDay(plan.startTime) ?: return null
    val end = PlanTime.minutesOfDay(plan.endTime) ?: return null
    if (start == end) return null
    val today = OccurrenceDate.from(now)
    // Eight years covers the longest Gregorian leap-day gap (e.g. 2096 -> 2104).
    for (offset in 0..8 * 366) {
        val date = today.plusDays(offset)
        if (!PlanOccurrence.occursOn(plan, date)) continue
        val candidate = (now.clone() as Calendar).apply {
            clear()
            set(date.year, date.month - 1, date.day, start / 60, start % 60, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (candidate.timeInMillis > now.timeInMillis) return candidate
    }
    return null
}
