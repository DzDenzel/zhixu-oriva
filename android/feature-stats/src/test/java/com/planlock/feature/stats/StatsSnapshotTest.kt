package com.planlock.feature.stats

import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.TodaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatsSnapshotTest {
    private val stats = ApiResponse(data = StatsResponse(completedCount = 3))
    private val today = ApiResponse(data = TodaySummary(executions = listOf(ExecutionRecordDto(id = 7))))

    @Test
    fun successfulResponsesKeepStatsAndAllowEmptyExecutions() {
        assertEquals(stats.data to today.data!!.executions, statsSnapshot(stats, today))
        assertEquals(emptyList<ExecutionRecordDto>(), statsSnapshot(stats, ApiResponse(data = TodaySummary())).second)
    }

    @Test
    fun eitherBusinessFailureRejectsEvenPresentData() {
        assertThrows(IllegalStateException::class.java) { statsSnapshot(stats.copy(code = 1401), today) }
        assertThrows(IllegalStateException::class.java) { statsSnapshot(stats, today.copy(code = 1401)) }
    }

    @Test
    fun missingPayloadIsNotAnEmptySuccessfulSnapshot() {
        assertThrows(IllegalStateException::class.java) { statsSnapshot(stats.copy(data = null), today) }
        assertThrows(IllegalStateException::class.java) { statsSnapshot(stats, today.copy(data = null)) }
    }
}
