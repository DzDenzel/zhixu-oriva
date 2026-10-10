package com.planlock.app.record

import org.junit.Assert.*
import org.junit.Test

class TimelineWindowTest {
    private fun entry(start: Int, end: Int) = TimelineEntry(TimelineKind.PLAN, 1, "计划", start, end, state = TimelineState.UPCOMING, sourceLabel = "计划")
    @Test fun windowPadsAndRoundsOutward() {
        assertEquals(TimelineWindow(420, 1260), DayTimeline.window(listOf(entry(485, 1190)), 600))
    }
    @Test fun minimumEightHoursContainsNowAtBothDayEdges() {
        assertEquals(TimelineWindow(0, 480), DayTimeline.window(emptyList(), 0))
        assertEquals(TimelineWindow(960, 1440), DayTimeline.window(emptyList(), 1439))
        assertEquals(480, DayTimeline.window(listOf(entry(600, 660)), 630).duration)
    }
    @Test fun bandUsesVisibleWindowRatio() {
        assertFalse(DayTimeline.isBackgroundBand(entry(0, 480), TimelineWindow(0, 1200)))
        assertTrue(DayTimeline.isBackgroundBand(entry(0, 800), TimelineWindow(0, 1200)))
        assertFalse(DayTimeline.isBackgroundBand(entry(0, 799), TimelineWindow(0, 1200)))
    }
    @Test fun crossingLabelsKeepOriginalTimes() {
        val e = entry(1260, 1440).copy(clippedEnd = true, originalStartMin = 1260, originalEndMin = 360)
        assertEquals("21:00 → 次日 06:00", DayTimeline.rangeLabel(e))
        assertEquals("← 昨日 21:00 → 06:00", DayTimeline.rangeLabel(e.copy(startMin = 0, endMin = 360, clippedStart = true, clippedEnd = false)))
        assertEquals("全天", DayTimeline.rangeLabel(entry(0, 1440).copy(sourceLabel = "全天日程")))
    }
}
