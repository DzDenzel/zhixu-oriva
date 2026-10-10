package com.planlock.app.sync

import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.StatsResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportScopeFetchTest {
    @Test
    fun coordinatedFetchLoadsEveryScopeExactlyOnce() = runBlocking {
        val requested = mutableListOf<String>()

        val result = fetchReportScopes { period ->
            requested += period
            ApiResponse(data = StatsResponse(period = period, completedCount = requested.size))
        }

        assertEquals(listOf("day", "week", "month"), requested)
        assertEquals(setOf("day", "week", "month"), result.keys)
        assertEquals(2, result.getValue("week").completedCount)
    }

    @Test
    fun reportTimestampAdvancesOnlyWhenACompleteReportSetReplacesTheSnapshot() {
        val original = SyncOverview(
            statsByPeriod = mapOf("week" to StatsResponse(period = "week")),
            updatedAtEpochMs = 10L,
        )
        val reports = mapOf(
            "day" to StatsResponse(period = "day"),
            "week" to StatsResponse(period = "week", completedCount = 2),
            "month" to StatsResponse(period = "month"),
        )

        val refreshed = original.withReportScopes(reports, refreshedAtEpochMs = 99L)

        assertEquals(10L, original.updatedAtEpochMs)
        assertEquals(99L, refreshed.updatedAtEpochMs)
        assertEquals(2, refreshed.stats?.completedCount)
        assertEquals(reports, refreshed.statsByPeriod)
    }

    @Test
    fun mismatchedServerPeriodCannotEnterTheAtomicReportBatch() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fetchReportScopes { requested ->
                    ApiResponse(data = StatsResponse(period = if (requested == "month") "week" else requested))
                }
            }
        }
    }
}
