package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PlanAlarmReconcilePlannerTest {
    private val scope = "local\nlocal"
    private val zone = TimeZone.getDefault()
    private fun time(value: String): Long = requireNotNull(
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.parse(value),
    ).time

    private fun plan(id: Long = 7L, status: Int = 1, endedDate: String? = null) =
        PlanSnapshotEntity(id, 0, "专注", "学习", "10:00", "11:00", null, null, null, 5, null, status, 0, endedOccurrenceDate = endedDate, scope = scope)

    @Test fun exactPermissionGrantUpgradesCachedInexactAlarmsAndInvalidateForcesReschedule() {
        val now = time("2026-10-20 09:00")
        val plans = listOf(plan())

        // Initially exactAllowed=false and punctualStart=false -> INEXACT cached.
        val initial = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = plans,
            now = now,
            punctualStart = false,
            exactAllowed = false,
            existingRegistry = emptyMap(),
            calendarFallbackNeedsReconcile = false,
        )
        assertEquals(3, initial.scheduleEntries.size)
        assertTrue(initial.scheduleEntries.all { it.mode == PlanAlarmDeliveryMode.INEXACT })

        val cachedRegistry = initial.scheduleEntries.associate { it.key to it.signature }

        // Permission granted -> signature changes from INEXACT to EXACT even without invalidate().
        val upgraded = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = plans,
            now = now,
            punctualStart = false,
            exactAllowed = true,
            existingRegistry = cachedRegistry,
            calendarFallbackNeedsReconcile = false,
        )
        assertEquals(3, upgraded.scheduleEntries.size)
        assertTrue(upgraded.scheduleEntries.all { it.mode == PlanAlarmDeliveryMode.EXACT })

        // With punctualStart=true and invalidate() clearing registry, all alarms register as ALARM_CLOCK.
        val afterInvalidate = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = plans,
            now = now,
            punctualStart = true,
            exactAllowed = true,
            existingRegistry = emptyMap(),
            calendarFallbackNeedsReconcile = false,
        )
        assertEquals(3, afterInvalidate.scheduleEntries.size)
        assertTrue(afterInvalidate.scheduleEntries.all { it.mode == PlanAlarmDeliveryMode.ALARM_CLOCK })
    }

    @Test fun calendarFallbackReconcileIsNeverSkippedByMatchingAlarmSignatureCache() {
        val now = time("2026-10-20 09:00")
        val plans = listOf(plan())
        val first = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = plans,
            now = now,
            punctualStart = true,
            exactAllowed = true,
            existingRegistry = emptyMap(),
            calendarFallbackNeedsReconcile = true,
        )
        val cachedRegistry = first.scheduleEntries.associate { it.key to it.signature }

        // Every alarm signature hits cache, yet reconcileCalendarFallback must remain true.
        val cachedHit = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = plans,
            now = now,
            punctualStart = true,
            exactAllowed = true,
            existingRegistry = cachedRegistry,
            calendarFallbackNeedsReconcile = true,
        )
        assertTrue(cachedHit.cancelKeys.isEmpty())
        assertTrue(cachedHit.scheduleEntries.isEmpty())
        assertTrue(cachedHit.reconcileCalendarFallback)
    }

    @Test fun disablingOrEndingPlanCancelsStaleAlarmsWhileKeepingCalendarReconcileEnabled() {
        val now = time("2026-10-20 10:30")
        val active = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = listOf(plan()),
            now = now,
            punctualStart = true,
            exactAllowed = true,
            existingRegistry = emptyMap(),
            calendarFallbackNeedsReconcile = true,
        )
        val cachedRegistry = active.scheduleEntries.associate { it.key to it.signature }

        val disabled = PlanAlarmReconcilePlanner.plan(
            scope = scope,
            plans = listOf(plan(status = 0)),
            now = now,
            punctualStart = true,
            exactAllowed = true,
            existingRegistry = cachedRegistry,
            calendarFallbackNeedsReconcile = true,
        )
        assertEquals(3, disabled.cancelKeys.size)
        assertTrue(disabled.scheduleEntries.isEmpty())
        assertTrue(disabled.reconcileCalendarFallback)
    }
}
