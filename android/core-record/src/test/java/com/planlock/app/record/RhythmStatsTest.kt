package com.planlock.app.record

import com.planlock.app.api.dto.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Catches denominator inflation, disabled days, repeated upload, date-boundary and sorting drift. */
class RhythmStatsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today = requireNotNull(OccurrenceDate.parseIso("2026-10-04"))

    @Test fun sharedGoldenFixtureMatchesEveryField() {
        val fixture = json.parseToJsonElement(fixtureFile().readText(Charsets.UTF_8)).jsonObject
        fixture.getValue("cases").jsonArray.forEach { item ->
            val test = item.jsonObject
            val plans = json.decodeFromJsonElement<List<PlanSnapshotEntity>>(test.getValue("plans"))
            val executions = json.decodeFromJsonElement<List<ExecutionRecordDto>>(test.getValue("executions"))
            val behavior = json.decodeFromJsonElement<List<RhythmBehavior>>(test.getValue("behavior"))
            val expected = json.decodeFromJsonElement<RhythmSummary>(test.getValue("expected"))
            assertEquals(test.getValue("name").jsonPrimitive.content, expected,
                RhythmStats.aggregate(plans, executions, behavior, today, source = RhythmSource.SYNCED))
        }
    }

    @Test fun zeroDenominatorIsRepresentedAsZeroOverZero() {
        val summary = RhythmStats.aggregate(emptyList(), emptyList(), emptyList(), today)
        assertEquals(Ratio(0, 0), summary.completionRate7d)
        assertEquals(62, summary.days.size)
        assertNull(summary.distraction)
        assertEquals("2026-08-04", summary.days.first().date)
        assertTrue(summary.days.last().isToday)
    }

    @Test fun negativeDurationsAndNonTerminalRowsNeverCreateFocusOrMissedOccurrences() {
        val row = ExecutionRecordDto(planId = 9, planDate = "2026-10-04", status = 3)
        val summary = RhythmStats.aggregate(emptyList(), listOf(row, row.copy(status = 0),
            row.copy(id = 3, status = 1, startedAt = "2026-10-04T10:00:00", endedAt = "2026-10-04T09:00:00")), emptyList(), today)
        assertEquals(0, summary.focusMinutes30d)
        assertEquals(Ratio(1, 1), summary.completionRate7d)
        assertEquals(0, summary.days.last().missed)
    }

    @Test fun streakContinuesFromYesterdayWhenTodayIsStillEmptyAndIsNotCappedAtThirtyDays() {
        val rows = (1..40).map { offset ->
            ExecutionRecordDto(id = offset.toLong(), planId = 9, planDate = today.plusDays(-offset).iso(), status = 1)
        }
        assertEquals(40, RhythmStats.aggregate(emptyList(), rows, emptyList(), today).streakDays)
    }

    @Test fun behaviorUsesHalfOpenSevenDayBoundaryAndStableTopThree() {
        val rows = listOf(
            RhythmBehavior(clientId = "one", appName = "A", packageName = "a", startedAt = "2026-09-28T00:00:00", endedAt = "2026-09-28T00:01:00", durationMs = 60_000),
            RhythmBehavior(clientId = "next", appName = "tomorrow", packageName = "z", startedAt = "2026-10-05T00:00:00", endedAt = "2026-10-05T00:01:00", durationMs = 60_000),
        )
        val stats = RhythmStats.aggregate(emptyList(), emptyList(), rows + rows.first(), today)
        assertEquals(Distraction(1, listOf(DistractionSource("A", 1))), stats.distraction)
    }

    private fun OccurrenceDate.iso() = "%04d-%02d-%02d".format(year, month, day)

    @Test fun historicalHostRowsDoNotInflateDistractionAndSummariesKeepThirtyDaySemantics() {
        val own = RhythmBehavior(clientId = "host", packageName = "com.planlock.app", appName = "知序", startedAt = "2026-10-04T09:00:00", endedAt = "2026-10-04T10:00:00", durationMs = 3_600_000)
        assertNull(RhythmStats.aggregate(emptyList(), emptyList(), listOf(own), today).distraction)
        val shorter = RhythmStats.aggregate(emptyList(), emptyList(), emptyList(), today, days = 30)
        val longer = RhythmStats.aggregate(emptyList(), emptyList(), emptyList(), today)
        assertEquals(shorter.focusMinutes30d, longer.focusMinutes30d)
        assertEquals(shorter.completionRate7d, longer.completionRate7d)
        assertEquals(shorter.days, longer.days.takeLast(30))
    }
    private fun fixtureFile(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "test-fixtures/rhythm-golden.json") }.first { it.isFile }
}
