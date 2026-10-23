package com.planlock.feature.stats

import com.planlock.app.api.dto.DayCell
import com.planlock.app.api.dto.Ratio
import com.planlock.app.record.OccurrenceDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmPresentationTest {
    // 2026-10-19 是周一
    private val today = OccurrenceDate.parseIso("2026-10-19")!!

    /** 30 天：9/20 – 10/19，每天计划 3 次；今天完成 1、还有 2 次没到时间。 */
    private val days = (0 until 30).map { i ->
        val d = today.plusDays(i - 29)
        if (i == 29) DayCell(d.iso(), 3, 1, 0, 0, 30, true)
        else DayCell(d.iso(), 3, 2, if (i % 5 == 0) 1 else 0, if (i % 5 == 0) 0 else 1, 90, false)
    }

    @Test fun weekStartsMondayAndFutureDaysAreEmpty() {
        val week = rhythmPeriod(RhythmRange.WEEK, 0, days, today)
        assertEquals(7, week.bars.size)
        assertEquals("2026-10-19", week.bars.first().date.iso())
        assertTrue(week.bars.first().isToday)
        assertTrue(week.bars.drop(1).all { it.future && it.cell == null })
        assertEquals(2, week.bars.first().upcoming)
        assertEquals("本周完成 1 / 3 次，还有 2 次没到时间", rhythmPeriodSummary(week, rhythmRangeLabel(week, today)))
        assertTrue(week.canGoBack)
        assertFalse(week.canGoForward)
    }

    @Test fun previousWeekLabelAndAverage() {
        val week = rhythmPeriod(RhythmRange.WEEK, -1, days, today)
        assertEquals("10 月 12 日 – 18 日", rhythmRangeLabel(week, today))
        assertEquals(7, week.knownDays)
        assertEquals(14, week.completed)
        assertTrue(rhythmPeriodSummary(week, rhythmRangeLabel(week, today)).startsWith("这周完成 14 / 21 次；日均 2.0 次"))
    }

    @Test fun weekAcrossMonthsAndWindowEdge() {
        val week = rhythmPeriod(RhythmRange.WEEK, -3, days, today) // 9/28 – 10/4
        assertEquals("9 月 28 日 – 10 月 4 日", rhythmRangeLabel(week, today))
        val oldest = rhythmPeriod(RhythmRange.WEEK, -4, days, today) // 9/21 – 9/27，窗口从 9/20 开始
        assertTrue(oldest.canGoBack)
        assertFalse(rhythmPeriod(RhythmRange.WEEK, -5, days, today).canGoBack)
    }

    @Test fun monthIsCalendarMonth() {
        val month = rhythmPeriod(RhythmRange.MONTH, 0, days, today)
        assertEquals(31, month.bars.size)
        assertEquals("2026-10-01", month.bars.first().date.iso())
        assertEquals("本月", rhythmRangeLabel(month, today))
        assertEquals(12, month.bars.count { it.future })
        val september = rhythmPeriod(RhythmRange.MONTH, -1, days, today)
        assertEquals(30, september.bars.size)
        assertEquals("9 月", rhythmRangeLabel(september, today))
        assertEquals(11, september.knownDays) // 9/20 – 9/30
        assertFalse(september.canGoBack)
    }

    @Test fun dayLineReadsNaturally() {
        val week = rhythmPeriod(RhythmRange.WEEK, 0, days, today)
        assertEquals("10 月 19 日 周一（今天）：完成 1 / 3，还有 2 次没到时间；专注 30 分钟", rhythmDayLine(week.bars[0]))
        assertEquals("10 月 20 日 周二：还没到", rhythmDayLine(week.bars[1]))
    }

    @Test fun minutesAndDeltas() {
        assertEquals("45 分钟", minutesLabel(45))
        assertEquals("1 小时 20 分", minutesLabel(80))
        assertEquals("28.5 小时", minutesLabel(1710))
        assertEquals("12 小时", minutesLabel(720))
        assertEquals(Delta("比前 30 天多 2 小时 30 分", true, "+2小时30分"), focusDelta(1710, 1560))
        assertEquals(Delta("比前 30 天少 1 次", true, "−1次"), earlyDelta(2, 3))
        assertEquals(Delta("比前 30 天多 2 次", false, "+2次"), earlyDelta(3, 1))
        assertEquals("持平", focusDelta(30, 30).short)
        assertEquals("+1分钟", focusDelta(1, 0).short)
        assertNull(earlyDelta(0, 0).better)
        assertEquals("近 7 天还没有可计入的计划", rateDelta(Ratio(0, 0), Ratio(3, 5)).text)
        assertEquals(Delta("比上周高 17 个百分点", true), rateDelta(Ratio(14, 17), Ratio(13, 20)))
    }

    @Test fun heroLineCountsInsteadOfRepeatingStreak() {
        assertEquals("近 7 天完成 5 / 10 次", rhythmHeroLine(Ratio(5, 10)))
        assertEquals("近 7 天还没有可计入的计划", rhythmHeroLine(Ratio(0, 0)))
    }

    @Test fun emptyPeriodSaysNothingPlanned() {
        val quiet = (0 until 30).map { i -> DayCell(today.plusDays(i - 29).iso(), 0, 0, 0, 0, 0, i == 29) }
        val week = rhythmPeriod(RhythmRange.WEEK, 0, quiet, today)
        assertEquals("本周没有安排计划", rhythmPeriodSummary(week, rhythmRangeLabel(week, today)))
        val month = rhythmPeriod(RhythmRange.MONTH, 0, quiet, today)
        assertEquals("本月没有安排计划", rhythmPeriodSummary(month, rhythmRangeLabel(month, today)))
    }
}
