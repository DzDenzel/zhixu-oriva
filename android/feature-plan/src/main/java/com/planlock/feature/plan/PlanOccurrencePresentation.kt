package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.entity.ExecutionHistoryEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.design.PlanVisualState
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Read only: terminal execution facts never change the scheduler or a plan's enabled state. */
internal fun PlanSnapshotEntity.visualState(
    clock: Calendar,
    executionHistory: List<ExecutionHistoryEntity> = emptyList(),
): PlanVisualState {
    if (status != 1) return PlanVisualState.PAUSED
    val start = PlanTime.minutesOfDay(startTime) ?: return PlanVisualState.UPCOMING
    val end = PlanTime.minutesOfDay(endTime) ?: return PlanVisualState.UPCOMING
    if (start == end || !PlanOccurrence.isSupportedRule(repeatRule)) return PlanVisualState.UPCOMING
    val minutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    // Keep an overnight result attached to its start day until the next start.
    val day = OccurrenceDate.from(clock).let { if (end < start && minutes < start) it.previous() else it }
    if (!PlanOccurrence.occursOn(this, day)) return PlanVisualState.UPCOMING
    val startClock = (clock.clone() as Calendar).apply {
        clear(); set(day.year, day.month - 1, day.day, start / 60, start % 60)
    }
    if (clock.timeInMillis < startClock.timeInMillis) return PlanVisualState.UPCOMING
    val date = day.isoDate()
    if (endedOccurrenceDate == date) return PlanVisualState.TODAY_ENDED
    val terminal = executionHistory.asSequence()
        .filter { it.scope == scope && it.planId == id && it.type == "execution" && it.planDate == date && it.status in listOf(1, 2) }
        .mapNotNull { row ->
            val ended = recordTimeMillis(row.endedAt, clock) ?: return@mapNotNull null
            // Old results cannot complete a plan rescheduled to a later start, nor a future record.
            if (ended < startClock.timeInMillis || ended > clock.timeInMillis) return@mapNotNull null
            row to ended
        }
        .maxWithOrNull(compareBy<Pair<ExecutionHistoryEntity, Long>> { it.second }
            // On a tied/conflicting terminal prefer the explicit early end, never overclaim completion.
            .thenBy { it.first.status })?.first
    return when (terminal?.status) {
        1 -> PlanVisualState.TODAY_DONE
        2 -> PlanVisualState.TODAY_ENDED
        else -> if (PlanOccurrence.isActive(this, minutes, OccurrenceDate.from(clock))) PlanVisualState.EXECUTING else PlanVisualState.UPCOMING
    }
}

internal fun executionHistoryDates(clock: Calendar): Pair<String, String> {
    val today = OccurrenceDate.from(clock)
    return today.previous().isoDate() to today.isoDate()
}

private fun OccurrenceDate.isoDate() = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)

private val recordTimestamp = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?$""")
private fun recordTimeMillis(raw: String?, clock: Calendar): Long? {
    val match = recordTimestamp.matchEntire(raw ?: return null) ?: return null
    val fraction = match.groupValues[2]
    val zone = match.groupValues[3]
    val normalized = match.groupValues[1] + (if (fraction.isEmpty()) "" else "." + fraction.take(3).padEnd(3, '0')) + zone
    val pattern = "yyyy-MM-dd'T'HH:mm:ss" + (if (fraction.isEmpty()) "" else ".SSS") + (if (zone.isEmpty()) "" else "XXX")
    val parser = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false; timeZone = clock.timeZone }
    val position = ParsePosition(0)
    return parser.parse(normalized, position)?.takeIf { position.index == normalized.length }?.time
}
