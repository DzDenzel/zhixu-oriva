package com.planlock.feature.stats

import com.planlock.app.api.dto.DailyStat
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsWeekTest {
    @Test
    fun missingDatesAreFilledInsideRollingSevenDayWindow() {
        val result = normalizedWeek(
            listOf(
                DailyStat("2026-09-03", 2),
                DailyStat("2026-09-05", 4),
                DailyStat("2026-09-09", 1)
            ),
            today = "2026-09-09"
        )

        assertEquals((3..9).map { "2026-09-%02d".format(it) }, result.map { it.date })
        assertEquals(listOf(2, 0, 4, 0, 0, 0, 1), result.map { it.completed })
    }
}
