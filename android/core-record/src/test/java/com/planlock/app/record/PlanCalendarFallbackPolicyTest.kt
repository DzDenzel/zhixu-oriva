package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PlanCalendarFallbackPolicyTest {
    private val scope = "local\nlocal"
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(value: String): Long = requireNotNull(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        .apply { timeZone = zone }.parse(value)).time
    private fun plan(id: Long = -1, start: String = "10:00", end: String = "11:00", lead: Int? = 5, rule: String? = null) =
        PlanSnapshotEntity(id, 0, "阅读", "学习", start, end, rule, null, null, lead, null, 1, 0, scope = scope)
    private fun expand(plan: PlanSnapshotEntity = plan(), now: String = "2026-10-20 09:00", count: Int = 2,
        suppressed: Set<String> = emptySet()) =
        PlanCalendarFallbackPolicy.expand(scope, listOf(plan), time(now), suppressed, zone, count)

    @Test fun occurrenceKeyRoundTripsDelimiterContainingScopeAndTemporaryIds() {
        val key = PlanCalendarOccurrenceKey("server:https://example.test:443\nuser:42", -12, 123456)
        assertEquals(key, PlanCalendarOccurrenceKey.decode(key.encode()))
        assertNotEquals(key.encode(), key.copy(scope = "other").encode())
        assertNotEquals(key.encode(), key.copy(planId = 12).encode())
        assertNotEquals(key.encode(), key.copy(startAt = 123457).encode())
    }

    @Test fun malformedPersistentKeysAreRejected() {
        listOf("", "bad", "1", "-1:x:1:2", "999:x:1:2", "1:x:y:2", "1:x:1:2:3", "1:xx:1:2", "0::1:x")
            .forEach { assertNull(it, PlanCalendarOccurrenceKey.decode(it)) }
    }

    @Test fun positiveAdvanceUsesActualOccurrenceAndOneMinuteLessAdvance() {
        val events = expand()
        assertEquals(2, events.size)
        assertEquals(time("2026-10-20 10:00"), events[0].startAt)
        assertEquals(time("2026-10-20 11:00"), events[0].endAt)
        assertEquals(time("2026-10-20 10:00"), events[0].key.startAt)
        assertEquals(4, events[0].reminderMinutes)
        assertEquals(time("2026-10-20 09:56"), events[0].startAt - requireNotNull(events[0].reminderMinutes) * 60_000L)
        assertEquals(time("2026-10-21 10:00"), events[1].key.startAt)
    }

    @Test fun zeroAndOneMinuteEdgesNeverUseNegativeReminderMinutes() {
        for (lead in listOf(null, 0, 1, 2, 5, 60, Int.MAX_VALUE)) {
            val minutes = PlanCalendarFallbackPolicy.reminderMinutes(lead)
            val shift = PlanCalendarFallbackPolicy.eventShiftMs(lead)
            assertTrue(minutes >= 0)
            // Compared with the ORIVA slot, the calendar slot is exactly one minute later.
            val ownSlot = -(lead ?: 0).toLong() * 60_000L
            val backupSlot = shift - minutes.toLong() * 60_000L
            assertEquals("lead=$lead", 60_000L, backupSlot - ownSlot)
        }
        assertEquals(0, PlanCalendarFallbackPolicy.reminderMinutes(1))
        assertEquals(0L, PlanCalendarFallbackPolicy.eventShiftMs(1))
    }

    @Test fun zeroLeadShiftsBothEventBoundariesButKeepsOriginalMapping() {
        for (lead in listOf(null, 0)) {
            val event = expand(plan(lead = lead)).first()
            assertEquals(time("2026-10-20 10:00"), event.key.startAt)
            assertEquals(time("2026-10-20 10:01"), event.startAt)
            assertEquals(time("2026-10-20 11:01"), event.endAt)
            assertEquals(0, event.reminderMinutes)
        }
    }

    @Test fun shortZeroLeadOccurrenceSurvivesUntilShiftedCalendarEnd() {
        val event = expand(plan(end = "10:01", lead = 0), "2026-10-20 10:01", count = 1).single()
        assertEquals(time("2026-10-20 10:00"), event.key.startAt)
        assertEquals(time("2026-10-20 10:02"), event.endAt)
        val next = expand(plan(end = "10:01", lead = 0), "2026-10-20 10:02", count = 1).single()
        assertEquals(time("2026-10-21 10:00"), next.key.startAt)
    }

    @Test fun endedTodayIsSkippedAndOvernightTailUsesOriginalStartDate() {
        val p = plan(start = "23:00", end = "01:00")
        val current = expand(p, "2026-10-21 00:30", count = 1).single()
        assertEquals(time("2026-10-20 23:00"), current.key.startAt)
        assertEquals(time("2026-10-21 01:00"), current.endAt)
        val next = expand(p.copy(endedOccurrenceDate = "2026-10-20"), "2026-10-21 00:30", count = 1).single()
        assertEquals(time("2026-10-21 23:00"), next.key.startAt)
    }

    @Test fun weeklyAndYearlyRulesReuseCanonicalExpansion() {
        val weekly = expand(plan(rule = "{\"type\":\"weekly\",\"weekdays\":[1]}"))
        assertEquals(time("2026-10-26 10:00"), weekly[0].key.startAt)
        assertEquals(time("2026-11-02 10:00"), weekly[1].key.startAt)
        val yearly = expand(plan(rule = "{\"type\":\"yearly\",\"month\":1,\"day\":1}"), count = 1).single()
        assertEquals(time("2027-01-01 10:00"), yearly.key.startAt)
    }

    @Test fun disabledInvalidAndWrongScopePlansCreateNoEvents() {
        assertTrue(expand(plan().copy(status = 0)).isEmpty())
        assertTrue(expand(plan(rule = "invalid")).isEmpty())
        assertTrue(expand(plan().copy(scope = "other\nuser")).isEmpty())
        assertTrue(expand(plan(start = "bad")).isEmpty())
        assertTrue(expand(plan(lead = -1)).isEmpty())
    }

    @Test fun advanceAcrossMidnightMatchesCanonicalReminderSlot() {
        val p = plan(start = "00:10", end = "01:00", lead = 20)
        val event = expand(p, "2026-10-19 22:00", count = 1).single()
        val own = requireNotNull(PlanAlarmTime.next(p, PlanAlarmKind.REMINDER, time("2026-10-19 22:00"), zone))
        assertEquals(own.startAt, event.key.startAt)
        assertEquals(own.triggerAt + 60_000, event.startAt - requireNotNull(event.reminderMinutes) * 60_000L)
    }

    @Test fun suppressionRemovesOnlyMatchingScopedOccurrenceAndSurvivesProjection() {
        val original = expand()
        val suppressed = expand(suppressed = setOf(original.first().key.encode()))
        assertNull(suppressed.first().reminderMinutes)
        assertEquals(original[1], suppressed[1])
        assertEquals(original.first().copy(reminderMinutes = null), suppressed.first())
        assertEquals(suppressed, expand(suppressed = setOf(original.first().key.encode())))
    }

    @Test fun rollingWindowAddsNextOccurrenceAndRemovesExpiredEvent() {
        val before = expand()
        val after = expand(now = "2026-10-20 11:00")
        val diff = PlanCalendarFallbackPolicy.diff(before.mapIndexed { index, event -> PlanCalendarStoredEvent(10L + index, event) }, after)
        assertEquals(listOf(10L), diff.delete.map { it.eventId })
        assertEquals(listOf(time("2026-10-22 10:00")), diff.insert.map { it.key.startAt })
        assertTrue(diff.update.isEmpty())
    }

    @Test fun diffHandlesCreateDisableDeleteRenameAndChangedTimes() {
        val desired = expand(count = 1)
        assertEquals(desired, PlanCalendarFallbackPolicy.diff(emptyList(), desired).insert)
        val stored = listOf(PlanCalendarStoredEvent(9, desired.single()))
        val noChange = PlanCalendarFallbackPolicy.diff(stored, desired)
        assertTrue(noChange.delete.isEmpty() && noChange.insert.isEmpty() && noChange.update.isEmpty())
        assertEquals(stored, PlanCalendarFallbackPolicy.diff(stored, emptyList()).delete)
        val renamed = expand(plan().copy(name = "新名称"), count = 1)
        assertEquals(9L, PlanCalendarFallbackPolicy.diff(stored, renamed).update.single().first.eventId)
        val moved = expand(plan(start = "10:30"), count = 1)
        val changed = PlanCalendarFallbackPolicy.diff(stored, moved)
        assertEquals(stored, changed.delete)
        assertEquals(moved, changed.insert)
    }

    @Test fun diffRepairsDuplicateMappingsAndRemovesPreviousAccountEvents() {
        val desired = expand(count = 1)
        val original = PlanCalendarStoredEvent(1, desired.single())
        val duplicate = original.copy(eventId = 2)
        val otherScope = original.copy(eventId = 3, event = original.event.copy(key = original.event.key.copy(scope = "other")))
        val diff = PlanCalendarFallbackPolicy.diff(listOf(original, duplicate, otherScope), desired)
        assertEquals(listOf(2L, 3L), diff.delete.map { it.eventId })
        assertTrue(diff.insert.isEmpty() && diff.update.isEmpty())
    }

    @Test fun diffPersistsSuppressionWithoutDeletingTheVisibleCalendarEvent() {
        val desired = expand(count = 1)
        val stored = PlanCalendarStoredEvent(7, desired.single())
        val suppressed = desired.map { it.copy(reminderMinutes = null) }
        val diff = PlanCalendarFallbackPolicy.diff(listOf(stored), suppressed)
        assertTrue(diff.delete.isEmpty() && diff.insert.isEmpty())
        assertNull(diff.update.single().second.reminderMinutes)
    }

    @Test fun daylightSavingUsesCanonicalLocalCalendarBoundaries() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val instant = requireNotNull(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = ny }
            .parse("2026-03-07 23:00")).time
        val p = plan(start = "09:00", end = "10:00")
        val event = PlanCalendarFallbackPolicy.expand(scope, listOf(p), instant, zone = ny, count = 1).single()
        val canonical = requireNotNull(PlanAlarmTime.next(p, PlanAlarmKind.END, instant, ny))
        assertEquals(canonical.startAt, event.key.startAt)
        assertEquals(canonical.endAt, event.endAt)
        assertEquals(ny.id, event.timeZone)
    }
}
