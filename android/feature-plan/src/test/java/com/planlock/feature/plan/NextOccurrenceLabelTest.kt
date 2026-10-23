package com.planlock.feature.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class NextOccurrenceLabelTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }

    private val now = at(2026, 10, 21, 20, 0) // 周三

    @Test fun tomorrowWeekdayAndLater() {
        assertEquals("下一次：明天 06:00", nextOccurrenceLabel(at(2026, 10, 22, 6, 0), now))
        assertEquals("下一次：周一 06:00", nextOccurrenceLabel(at(2026, 10, 26, 6, 0), now))
        assertEquals("下一次：10 月 28 日 06:00", nextOccurrenceLabel(at(2026, 10, 28, 6, 0), now))
        assertEquals("下一次：今天 21:30", nextOccurrenceLabel(at(2026, 10, 21, 21, 30), now))
    }

    @Test fun noNextOrPastHidesTheLine() {
        assertNull(nextOccurrenceLabel(null, now))
        assertNull(nextOccurrenceLabel(at(2026, 10, 20, 6, 0), now))
    }
}
