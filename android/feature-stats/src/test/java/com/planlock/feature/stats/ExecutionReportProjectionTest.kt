package com.planlock.feature.stats

import com.planlock.app.api.dto.PlanExecutionStat
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.BehaviorAggregateEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionReportProjectionTest {

    @Test
    fun plannedActualAndThreeStatesUseTheSharedOccurrenceRule() {
        val daily = plan(7, "每日专注", "09:00", "10:00", "{\"type\":\"daily\"}")
        val weekly = plan(8, "周三复盘", "20:00", "20:30", "{\"type\":\"weekly\",\"weekdays\":[3]}")
        val dates = listOf(
            OccurrenceDate(2026, 9, 28, 1),
            OccurrenceDate(2026, 9, 29, 2),
            OccurrenceDate(2026, 9, 30, 3),
        )

        val rows = planReportRows(
            plans = listOf(daily, weekly),
            server = listOf(PlanExecutionStat(7, completedCount = 1, earlyEndedCount = 1, actualDurationMs = 2_400_000)),
            dates = dates,
        )

        assertEquals(10_800_000L, rows.first { it.planId == 7L }.plannedDurationMs)
        assertEquals(2_400_000L, rows.first { it.planId == 7L }.actualDurationMs)
        assertEquals(1, rows.first { it.planId == 7L }.missedCount)
        assertEquals(1_800_000L, rows.first { it.planId == 8L }.plannedDurationMs)
        assertEquals(1, rows.first { it.planId == 8L }.missedCount)
    }

    @Test
    fun missedCountNeverDropsBelowZeroWhenServerHasDuplicateCompletions() {
        val row = planReportRows(
            plans = listOf(plan(7, "专注", "09:00", "10:00", null)),
            server = listOf(PlanExecutionStat(7, completedCount = 3, earlyEndedCount = 1)),
            dates = listOf(OccurrenceDate(2026, 10, 4, 7)),
        ).single()

        assertEquals(0, row.missedCount)
    }

    @Test
    fun localRankingCombinesSourcesAndMarksApproximateDuration() {
        val ranking = localViolationRanking(listOf(
            BehaviorAggregateEntity(7, "app.chat", "ACCESSIBILITY", 600_000, 1, null),
            BehaviorAggregateEntity(8, "app.chat", "USAGE_STATS_APPROXIMATE", 1_200_000, 2, null),
            BehaviorAggregateEntity(7, "app.video", "ACCESSIBILITY", 900_000, 1, null),
            BehaviorAggregateEntity(99, "app.other-user", "ACCESSIBILITY", 9_000_000, 9, null),
        ), ownedPlanIds = setOf(7, 8))

        assertEquals(listOf("app.chat", "app.video"), ranking.map { it.packageName })
        assertEquals(1_800_000L, ranking[0].totalDurationMs)
        assertEquals(1_200_000L, ranking[0].approxDurationMs)
        assertEquals(3, ranking[0].count)
    }

    @Test
    fun planRowsAreOrderedByPlannedDurationDescending() {
        val rows = planReportRows(
            plans = listOf(
                plan(7, "短计划", "09:00", "09:30", null),
                plan(8, "长计划", "09:00", "11:00", null),
            ),
            server = emptyList(),
            dates = listOf(OccurrenceDate(2026, 10, 4, 7)),
        )

        assertEquals(listOf("长计划", "短计划"), rows.map { it.name })
    }

    @Test
    fun reportLabelsKeepEmptyAndOfflineStatesExplicit() {
        assertEquals("0:00:00", reportDurationLabel(0))
        assertEquals("0:01:01", reportDurationLabel(61_000))
        assertEquals("1:01:00", reportDurationLabel(3_660_000))
        assertEquals("1:00:01", reportDurationLabel(3_601_999))
        assertEquals("0:59:59", reportDurationLabel(3_599_999))
        assertEquals("0:00:00", reportDurationLabel(-1))
        assertEquals("本地数据 · 尚未同步", localDataLabel(null))
        assertEquals("10/04", scopeDateRangeLabel("day", OccurrenceDate(2026, 10, 4, 7)))
        assertEquals("09/28–10/04", scopeDateRangeLabel("week", OccurrenceDate(2026, 10, 4, 7)))
        assertEquals("10/01–10/04", scopeDateRangeLabel("month", OccurrenceDate(2026, 10, 4, 7)))
        assertEquals("已完成", executionStatusLabel(1))
        assertEquals("提前结束", executionStatusLabel(2))
        assertEquals("旧版异常结束", executionStatusLabel(3))
    }

    @Test
    fun reportScopesExpandTodayRollingWeekAndCurrentMonthWithoutNetworkState() {
        val today = OccurrenceDate(2026, 10, 4, 7)

        assertEquals(listOf(today), scopeDates("day", today))
        assertEquals(7, scopeDates("week", today).size)
        assertEquals(OccurrenceDate(2026, 9, 28, 1), scopeDates("week", today).first())
        assertEquals(4, scopeDates("month", today).size)
        assertEquals(OccurrenceDate(2026, 10, 1, 4), scopeDates("month", today).first())
    }

    @Test
    fun serverReportNeverMixesWithLocalCumulativeRanking() {
        val local = listOf(BehaviorAggregateEntity(7, "app.local", "ACCESSIBILITY", 600_000, 3, null))

        val online = reportPresentation(StatsResponse(violationDurationMs = 0, violationByApp = emptyList()), local)
        val offline = reportPresentation(null, local)

        assertEquals(0L, online.durationMs)
        assertEquals(emptyList<String>(), online.ranking.map { it.packageName })
        assertEquals(false, online.local)
        assertEquals(600_000L, offline.durationMs)
        assertEquals(listOf("app.local"), offline.ranking.map { it.packageName })
        assertEquals(true, offline.local)
    }

    private fun plan(id: Long, name: String, start: String, end: String, repeat: String?) = PlanSnapshotEntity(
        id = id,
        userId = 9,
        name = name,
        type = "focus",
        startTime = start,
        endTime = end,
        repeatRule = repeat,
        color = null,
        icon = null,
        remindAdvanceMin = null,
        remindMethods = null,
        status = 1,
        version = 1,
    )
}
