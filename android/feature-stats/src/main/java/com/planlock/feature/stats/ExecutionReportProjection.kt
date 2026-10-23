package com.planlock.feature.stats

import com.planlock.app.api.durationDisplayLabel

import com.planlock.app.api.dto.PlanExecutionStat
import com.planlock.app.api.dto.ViolationAppStat
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.LocalExecutionStats
import com.planlock.app.record.entity.BehaviorAggregateEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class PlanReportRow(
    val planId: Long,
    val name: String,
    val plannedDurationMs: Long,
    val actualDurationMs: Long,
    val completedCount: Int,
    val earlyEndedCount: Int,
    val missedCount: Int,
)

internal data class ReportPresentation(
    val durationMs: Long,
    val ranking: List<ViolationAppStat>,
    val local: Boolean,
)

internal fun planReportRows(
    plans: List<PlanSnapshotEntity>,
    server: List<PlanExecutionStat>,
    dates: List<OccurrenceDate>,
): List<PlanReportRow> {
    val byPlan = server.associateBy { it.planId }
    return plans.mapNotNull { plan ->
        val aggregate = byPlan[plan.id]
        val expected = LocalExecutionStats.expectedCount(plan, dates)
        if (plan.status != 1 && expected == 0 && aggregate == null) return@mapNotNull null
        val completed = aggregate?.completedCount ?: 0
        val early = aggregate?.earlyEndedCount ?: 0
        PlanReportRow(
            planId = plan.id,
            name = plan.name,
            plannedDurationMs = occurrenceDurationMs(plan) * expected,
            actualDurationMs = aggregate?.actualDurationMs ?: 0,
            completedCount = completed,
            earlyEndedCount = early,
            missedCount = (expected - completed - early).coerceAtLeast(0),
        )
    }.sortedWith(compareByDescending<PlanReportRow> { it.plannedDurationMs }.thenBy { it.name })
}

/** Cached behavior is period-scoped; execution fields are exclusively projected from local history. */
internal fun withCachedBehavior(
    execution: com.planlock.app.api.dto.StatsResponse,
    cached: com.planlock.app.api.dto.StatsResponse?,
): com.planlock.app.api.dto.StatsResponse = execution.copy(
    violationCount = cached?.violationCount ?: 0,
    violationDurationMs = cached?.violationDurationMs ?: 0,
    violationByApp = cached?.violationByApp.orEmpty(),
    legacyViolationCount = cached?.legacyViolationCount ?: 0,
)

internal fun localViolationRanking(
    values: List<BehaviorAggregateEntity>,
    ownedPlanIds: Set<Long>? = null,
): List<ViolationAppStat> = values
    .filter { ownedPlanIds == null || it.planId in ownedPlanIds }
    .groupBy { it.packageName }
    .map { (packageName, rows) ->
        ViolationAppStat(
            packageName = packageName,
            count = rows.sumOf { it.occurrenceCount }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            totalDurationMs = rows.sumOf { it.totalDurationMs },
            approxDurationMs = rows.filter { it.sourceLevel == "USAGE_STATS_APPROXIMATE" }.sumOf { it.totalDurationMs },
        )
    }
    .sortedWith(compareByDescending<ViolationAppStat> { it.totalDurationMs }.thenBy { it.packageName })

internal fun reportPresentation(
    server: com.planlock.app.api.dto.StatsResponse?,
    localValues: List<BehaviorAggregateEntity>,
    ownedPlanIds: Set<Long>? = null,
): ReportPresentation {
    if (server != null) return ReportPresentation(server.violationDurationMs, server.violationByApp, false)
    val ranking = localViolationRanking(localValues, ownedPlanIds)
    return ReportPresentation(ranking.sumOf { it.totalDurationMs }, ranking, true)
}

internal fun scopeDates(period: String, today: OccurrenceDate): List<OccurrenceDate> = when (period) {
    "day" -> listOf(today)
    "month" -> (1..today.day).map { offset -> today.plusDays(offset - today.day) }
    else -> (6 downTo 0).map { offset -> today.plusDays(-offset) }
}

internal fun reportDurationLabel(millis: Long): String {
    return durationDisplayLabel(millis)
}

internal fun localDataLabel(lastSyncAtMs: Long?): String = if (lastSyncAtMs == null) {
    "本地数据 · 尚未同步"
} else {
    "本地数据 · 上次同步 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSyncAtMs))}"
}

internal fun scopeDateRangeLabel(period: String, today: OccurrenceDate): String {
    val dates = scopeDates(period, today)
    fun OccurrenceDate.short() = "%02d/%02d".format(Locale.ROOT, month, day)
    return if (dates.size == 1) dates.single().short() else "${dates.first().short()}–${dates.last().short()}"
}

internal fun executionStatusLabel(status: Int?): String = when (status) {
    1 -> "已完成"
    2 -> "提前结束"
    else -> "旧版异常结束"
}

private fun occurrenceDurationMs(plan: PlanSnapshotEntity): Long {
    val start = minutesOfDay(plan.startTime) ?: return 0
    val end = minutesOfDay(plan.endTime) ?: return 0
    val minutes = when {
        end > start -> end - start
        end < start -> 24 * 60 - start + end
        else -> 0
    }
    return minutes * 60_000L
}

private fun minutesOfDay(value: String?): Int? {
    val parts = value?.split(':') ?: return null
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}
