package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity

internal data class PlanAlarmEntry(
    val key: String,
    val planId: Long,
    val alarm: PlanAlarm,
    val mode: PlanAlarmDeliveryMode,
    val signature: String,
)

internal data class PlanAlarmReconcilePlan(
    val reconcileCalendarFallback: Boolean,
    val cancelKeys: List<String>,
    val scheduleEntries: List<PlanAlarmEntry>,
)

/**
 * Pure reconciliation planner separating calendar fallback decision from alarm-registry signature caching.
 * Calendar fallback cleanup/sync is never skipped even when every alarm signature hits the cache.
 */
internal object PlanAlarmReconcilePlanner {
    fun plan(
        scope: String,
        plans: List<PlanSnapshotEntity>,
        now: Long,
        punctualStart: Boolean,
        exactAllowed: Boolean,
        existingRegistry: Map<String, String?>,
        calendarFallbackNeedsReconcile: Boolean,
    ): PlanAlarmReconcilePlan {
        val desired = plans.flatMap { plan ->
            PlanAlarmKind.entries.mapNotNull { kind ->
                PlanAlarmTime.next(plan, kind, now)?.let { alarm ->
                    val mode = planAlarmDeliveryMode(alarm.kind, punctualStart, exactAllowed)
                    val signature = "${alarm.triggerAt}:${mode.name}"
                    PlanAlarmEntry("$scope\n${plan.id}\n${kind.name}", plan.id, alarm, mode, signature)
                }
            }
        }.associateBy { it.key }
        val cancelKeys = existingRegistry.keys.filter { it !in desired }
        val scheduleEntries = desired.values.filter { existingRegistry[it.key] != it.signature }
        return PlanAlarmReconcilePlan(
            reconcileCalendarFallback = calendarFallbackNeedsReconcile,
            cancelKeys = cancelKeys,
            scheduleEntries = scheduleEntries,
        )
    }
}
