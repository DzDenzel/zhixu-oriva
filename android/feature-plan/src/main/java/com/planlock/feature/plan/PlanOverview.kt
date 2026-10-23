package com.planlock.feature.plan

import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity

data class UpcomingPlan(val plan: PlanSnapshotEntity, val dayOffset: Int)

data class PlanOverviewModel(
    val current: List<PlanSnapshotEntity>,
    val upcoming: List<UpcomingPlan>,
    val categoryCounts: Map<String, Int>,
    val scheduled: List<PlanSnapshotEntity>,
    val stored: List<PlanSnapshotEntity>,
)

object PlanOverview {
    fun build(plans: List<PlanSnapshotEntity>, nowMinutes: Int, today: OccurrenceDate, customTypes: List<com.planlock.app.api.PlanTypeDefinition> = emptyList()): PlanOverviewModel {
        val unique = plans.distinctBy { it.id }
        val scheduled = unique.filter(::isScheduled)
        val current = scheduled.filter { PlanTime.isActive(it, nowMinutes, today) }
            .sortedBy { PlanTime.minutesOfDay(it.startTime) }
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        val upcoming = scheduled.asSequence()
            .filterNot { it.id in currentIds }
            .mapNotNull { plan -> nextOffset(plan, nowMinutes, today)?.let { UpcomingPlan(plan, it) } }
            .sortedWith(compareBy<UpcomingPlan> { it.dayOffset }
                .thenBy { PlanTime.minutesOfDay(it.plan.startTime) ?: Int.MAX_VALUE }
                .thenBy { it.plan.name })
            .toList()
        val categoryCounts = scheduled.groupingBy { com.planlock.app.api.PlanTypes.label(it.type, customTypes) }
            .eachCount().toSortedMap()
        return PlanOverviewModel(
            current = current,
            upcoming = upcoming,
            categoryCounts = categoryCounts,
            scheduled = scheduled.sortedWith(compareBy<PlanSnapshotEntity> { it.type }.thenBy { PlanTime.minutesOfDay(it.startTime) }),
            stored = unique.filterNot(::isScheduled).sortedBy { it.name },
        )
    }

    private fun isScheduled(plan: PlanSnapshotEntity): Boolean {
        val start = PlanTime.minutesOfDay(plan.startTime) ?: return false
        val end = PlanTime.minutesOfDay(plan.endTime) ?: return false
        return plan.status == 1 && start != end
    }

    private fun nextOffset(plan: PlanSnapshotEntity, nowMinutes: Int, today: OccurrenceDate): Int? {
        val start = PlanTime.minutesOfDay(plan.startTime) ?: return null
        return (0..6).firstOrNull { offset ->
            PlanOccurrence.occursOn(plan, today.plusDays(offset)) && (offset > 0 || start > nowMinutes)
        }
    }
}
