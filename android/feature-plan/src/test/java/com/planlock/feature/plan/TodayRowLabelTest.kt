package com.planlock.feature.plan

import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v4.3 方案 B：卡片元信息第一项与最右侧状态。 */
class TodayRowLabelTest {
    private fun entry(kind: TimelineKind, state: TimelineState, source: String = "计划") =
        TimelineEntry(kind, 1, "x", 540, 600, state = state, sourceLabel = source)

    @Test fun kindUsesPlanTypeOrFallsBack() {
        assertEquals("学习", todayRowKind(entry(TimelineKind.PLAN, TimelineState.UPCOMING), "学习"))
        assertEquals("计划", todayRowKind(entry(TimelineKind.PLAN, TimelineState.UPCOMING), " "))
        assertEquals("计划", todayRowKind(entry(TimelineKind.PLAN, TimelineState.UPCOMING), null))
        assertEquals("日程", todayRowKind(entry(TimelineKind.SCHEDULE, TimelineState.UPCOMING, "日程"), "学习"))
    }

    @Test fun stateLabelPerStateAndNoneForAllDay() {
        assertEquals("待开始", todayRowStateLabel(entry(TimelineKind.PLAN, TimelineState.UPCOMING)))
        assertEquals("已结束", todayRowStateLabel(entry(TimelineKind.PLAN, TimelineState.ENDED)))
        assertEquals("已暂停", todayRowStateLabel(entry(TimelineKind.PLAN, TimelineState.PAUSED)))
        assertNull(todayRowStateLabel(TimelineEntry(TimelineKind.SCHEDULE, 2, "生日", 0, 1440, state = TimelineState.RUNNING, sourceLabel = "全天日程")))
    }
}
