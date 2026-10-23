package com.planlock.app.record

import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.UnlockRecordDto
import com.planlock.app.api.dto.StatsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalExecutionStatsTest {
    private val today = OccurrenceDate(2026, 10, 4, 7)

    @Test fun sharedGoldenFixtureMatchesBackendForEveryScope() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "backend/planlock-app/src/test/resources/stats-golden.json").isFile }
        val json = Json { ignoreUnknownKeys = true }
        val fixture = json.parseToJsonElement(File(root,
            "backend/planlock-app/src/test/resources/stats-golden.json").readText(Charsets.UTF_8)).jsonObject
        val date = requireNotNull(OccurrenceDate.parseIso(fixture.getValue("today").jsonPrimitive.content))
        val executions = json.decodeFromJsonElement<List<ExecutionRecordDto>>(fixture.getValue("executions"))
        val unlocks = json.decodeFromJsonElement<List<UnlockRecordDto>>(fixture.getValue("unlocks"))
        listOf("day", "week", "month").forEach { period ->
            val expected = json.decodeFromJsonElement<StatsResponse>(fixture.getValue("expected").jsonObject.getValue(period))
            assertEquals(period, expected, LocalExecutionStats.aggregate(executions, unlocks, period, date))
        }
    }

    @Test fun scopesPreserveBackendStreakBoundaryAcrossMonthAndWeek() {
        val values = (27..30).map { record("2026-09-$it") } +
            (1..3).map { record("2026-10-0$it") }
        val week = LocalExecutionStats.aggregate(values, emptyList(), "WEEK", today)
        val month = LocalExecutionStats.aggregate(values, emptyList(), "month", today)
        assertEquals(6, week.completedCount)
        assertEquals(6, week.continuousDays)
        assertEquals(3, month.completedCount)
        assertEquals(3, month.continuousDays)
        assertEquals(0, LocalExecutionStats.aggregate(values, emptyList(), "day", today).continuousDays)
    }

    @Test fun localAndRemoteCopiesCountOnceWhileDifferentDevicesRemainSeparate() {
        val local = record("2026-10-04").copy(clientId = "same", deviceId = 1)
        val values = listOf(local, local.copy(id = 100), local.copy(id = 101, deviceId = 2, clientId = "other"))
        val stats = LocalExecutionStats.aggregate(values, emptyList(), "day", today)
        assertEquals(2, stats.completedCount)
        assertEquals(2, stats.daily!!.single().completed)
        assertEquals(2, stats.planExecution.single().completedCount)
    }

    @Test fun terminalStatusesOnlyContributeAndDurationUsesBothEndpoints() {
        val values = listOf(
            record("2026-10-04").copy(id = 1, startedAt = "2026-10-04T23:50:00", endedAt = "2026-10-05T00:10:00"),
            record("2026-10-04").copy(id = 2, status = 2, startedAt = "2026-10-04T09:00:00.123456", endedAt = "2026-10-04T09:00:01.124456"),
            record("2026-10-04").copy(id = 3, status = 3),
            record("2026-10-04").copy(id = 4, status = 0),
            record("2026-10-04").copy(id = 5, status = 1),
        )
        val stats = LocalExecutionStats.aggregate(values, emptyList(), "day", today)
        assertEquals(2, stats.completedCount)
        assertEquals(1, stats.planExecution.single().earlyEndedCount)
        assertEquals(1_201_001L, stats.planExecution.single().actualDurationMs)
        assertEquals(listOf(1, 3, 2, 1), LocalExecutionStats.todayExecutions(values, today).map { it.status })
    }

    @Test fun earlyEndingListIsNewestFiftyButUnlockCountIsUncapped() {
        val values = (0..54).map { minute -> UnlockRecordDto(id = minute + 1L, planId = 7,
            unlockAt = "2026-10-04T09:%02d:00".format(minute), method = "manual") }
        val stats = LocalExecutionStats.aggregate(emptyList(), values, "day", today)
        assertEquals(55, stats.unlockCount)
        assertEquals(50, stats.earlyEndings.size)
        assertEquals("2026-10-04T09:54:00", stats.earlyEndings.first().at)
        assertEquals("2026-10-04T09:05:00", stats.earlyEndings.last().at)
    }

    @Test fun backendInclusiveUnlockBoundaryAndIdempotencyArePreserved() {
        val ending = UnlockRecordDto(id = 1, planId = 7, unlockAt = "2026-10-05T00:00:00", clientId = "unlock")
        val stats = LocalExecutionStats.aggregate(emptyList(), listOf(ending, ending.copy(id = 2)), "day", today)
        assertEquals(1, stats.unlockCount)
    }

    private fun record(date: String) = ExecutionRecordDto(planId = 7, planDate = date, status = 1)
}
