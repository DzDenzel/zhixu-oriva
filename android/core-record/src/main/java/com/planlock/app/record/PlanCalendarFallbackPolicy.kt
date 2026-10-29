package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import java.util.TimeZone

/** Scope and occurrence start are part of identity; plan IDs alone are not globally unique. */
internal data class PlanCalendarOccurrenceKey(val scope: String, val planId: Long, val startAt: Long) {
    fun encode(): String = "${scope.length}:$scope:$planId:$startAt"

    companion object {
        fun decode(value: String): PlanCalendarOccurrenceKey? {
            val separator = value.indexOf(':')
            val length = value.substring(0, separator.coerceAtLeast(0)).toIntOrNull() ?: return null
            if (length < 0 || length > value.length - separator - 2) return null
            val end = separator + 1 + length
            if (value.getOrNull(end) != ':') return null
            val parts = value.substring(end + 1).split(':')
            if (parts.size != 2) return null
            return PlanCalendarOccurrenceKey(value.substring(separator + 1, end),
                parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null)
        }
    }
}

internal data class PlanCalendarEvent(
    val key: PlanCalendarOccurrenceKey,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val timeZone: String,
    val reminderMinutes: Int?,
)

internal data class PlanCalendarStoredEvent(val eventId: Long, val event: PlanCalendarEvent)
internal data class PlanCalendarSyncDiff(
    val delete: List<PlanCalendarStoredEvent>,
    val insert: List<PlanCalendarEvent>,
    val update: List<Pair<PlanCalendarStoredEvent, PlanCalendarEvent>>,
)

/** Pure projection: repeat, ended-today and overnight decisions remain in PlanAlarmTime. */
internal object PlanCalendarFallbackPolicy {
    const val MINUTE_MS = 60_000L
    const val UPCOMING_OCCURRENCES = 32

    // CalendarContract supports nonnegative advance minutes, not "one minute after".
    fun reminderMinutes(leadMinutes: Int?): Int = (leadMinutes ?: 0).coerceAtLeast(1) - 1
    fun eventShiftMs(leadMinutes: Int?): Long = if ((leadMinutes ?: 0) <= 0) MINUTE_MS else 0L

    fun expand(
        scope: String,
        plans: List<PlanSnapshotEntity>,
        now: Long,
        suppressed: Set<String> = emptySet(),
        zone: TimeZone = TimeZone.getDefault(),
        count: Int = UPCOMING_OCCURRENCES,
    ): List<PlanCalendarEvent> = buildList {
        for (plan in plans) {
            if (plan.scope != scope) continue
            // A zero-lead event is shifted by one minute, including its end. Keep that
            // final minute even when the ORIVA occurrence has just finished.
            var after = now - eventShiftMs(plan.remindAdvanceMin)
            var remaining = count
            while (remaining > 0) {
                val alarm = PlanAlarmTime.next(plan, PlanAlarmKind.END, after, zone) ?: break
                val key = PlanCalendarOccurrenceKey(scope, plan.id, alarm.startAt)
                val shift = eventShiftMs(plan.remindAdvanceMin)
                if (alarm.endAt + shift > now) {
                    add(PlanCalendarEvent(key, plan.name, alarm.startAt + shift, alarm.endAt + shift,
                        zone.id, if (key.encode() in suppressed) null else reminderMinutes(plan.remindAdvanceMin)))
                    remaining--
                }
                after = alarm.triggerAt
            }
        }
    }

    fun diff(existing: List<PlanCalendarStoredEvent>, desired: List<PlanCalendarEvent>): PlanCalendarSyncDiff {
        val wanted = desired.associateBy { it.key }
        val retained = mutableMapOf<PlanCalendarOccurrenceKey, PlanCalendarStoredEvent>()
        val delete = mutableListOf<PlanCalendarStoredEvent>()
        for (stored in existing) {
            if (stored.event.key !in wanted || retained.putIfAbsent(stored.event.key, stored) != null) delete += stored
        }
        return PlanCalendarSyncDiff(delete,
            desired.filter { it.key !in retained },
            desired.mapNotNull { event -> retained[event.key]?.takeIf { it.event != event }?.let { it to event } })
    }
}
