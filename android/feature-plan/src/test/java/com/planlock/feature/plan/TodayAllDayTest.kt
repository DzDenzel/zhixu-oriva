package com.planlock.feature.plan

import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayAllDayTest {
    private val date = "2026-10-16"
    private fun snapshot(id: Long, title: String, start: String? = null, end: String? = null) =
        ScheduleSnapshotEntity(id, 1, title, date, start, date, end, start == null,
            null, null, null, null, null, false, "Asia/Shanghai", 1, 0)

    @Test fun realTimelineKeepsTimedScheduleBesideTwoAllDaySchedules() {
        val schedules = listOf(snapshot(1, "生日"), snapshot(2, "出差"), snapshot(3, "阅读", "09:00", "10:00"))
        for (now in listOf(480, 570)) {
            val entries = todayTimeline(date, emptyList(), schedules, now)
            assertEquals(listOf("生日", "出差", "阅读"), entries.map { it.title })
            assertEquals(listOf("阅读"), todayDialLayers(entries).arcs.map { it.title })
            assertEquals("生日", todayDialLayers(entries).allDayRing?.title)
            assertEquals(if (now == 480) "下一项 09:00「阅读」。" else "正在进行「阅读」，专心就好。", todayBriefLine(entries, true))
        }
    }

    @Test fun realTimelineKeepsThreeOverlappingTimedSchedules() {
        val schedules = (1L..3L).map { snapshot(it, "会议$it", "09:00", "10:00") }
        val entries = todayTimeline(date, emptyList(), schedules, 480)
        assertEquals(listOf("会议1", "会议2", "会议3"), entries.map { it.title })
        assertEquals(entries, todayDialLayers(entries).arcs)
        assertEquals("下一项 09:00「会议1」，之后还有 2 项。", todayBriefLine(entries, true))
    }

    private fun allDay(id: Long, title: String) =
        TimelineEntry(TimelineKind.SCHEDULE, id, title, 0, 1440, state = TimelineState.RUNNING, sourceLabel = "全天日程")
    private fun timed(id: Long, title: String, start: Int, end: Int, state: TimelineState, kind: TimelineKind = TimelineKind.PLAN) =
        TimelineEntry(kind, id, title, start, end, state = state, sourceLabel = if (kind == TimelineKind.PLAN) "计划" else "日程")

    private val birthday = allDay(1, "某人生日")
    private val trip = allDay(2, "出差")

    // §3.1 情形 1：有正在进行的定时安排 → 原句式，不提全天日程
    @Test fun runningTimedWins() {
        val run = timed(10, "Run 5k", 900, 960, TimelineState.RUNNING)
        assertEquals("正在进行「Run 5k」，专心就好。", todayBriefLine(listOf(birthday, run), isToday = true))
    }

    // §3.1 情形 2：无进行中、有下一项定时安排 → 原句式，计数只算定时安排
    @Test fun nextTimedWins() {
        val a = timed(10, "Run 5k", 1080, 1140, TimelineState.UPCOMING)
        val b = timed(11, "读书", 1200, 1260, TimelineState.UPCOMING, TimelineKind.SCHEDULE)
        assertEquals("下一项 18:00「Run 5k」，之后还有 1 项。", todayBriefLine(listOf(birthday, a, b), isToday = true))
    }

    // §3.1 情形 3：只有全天日程（1 项）
    @Test fun singleAllDay() {
        val line = todayBriefLine(listOf(birthday), isToday = true)
        assertEquals("今天：「某人生日」。", line)
        assertFalse(line!!.contains("正在进行") || line.contains("专心就好"))
    }

    // §3.1 情形 4：只有全天日程（≥ 2 项）
    @Test fun severalAllDay() {
        assertEquals("今天：「某人生日」等 2 项。", todayBriefLine(listOf(birthday, trip), isToday = true))
    }

    // §3.1 情形 5：什么都没有 → 原空态
    @Test fun nothing() {
        assertNull(todayBriefLine(emptyList(), isToday = true))
        assertNull(todayBriefLine(listOf(birthday), isToday = false))
    }

    // 定时安排都已结束、仍有全天日程：讲全天日程，不说「都结束了」
    @Test fun endedTimedWithAllDay() {
        val done = timed(10, "English", 360, 450, TimelineState.ENDED)
        assertEquals("今天：「某人生日」。", todayBriefLine(listOf(done, birthday), isToday = true))
        assertEquals("今天的安排都结束了。", todayBriefLine(listOf(done), isToday = true))
    }

    // 反例防线：全天日程不参与任何「正在进行」判断
    @Test fun allDayIsNeverRunning() {
        assertTrue(birthday.isAllDay)
        assertFalse(birthday.isRunningNow)
        assertTrue(timed(10, "Run", 900, 960, TimelineState.RUNNING).isRunningNow)
        assertFalse(timed(11, "会议", 900, 960, TimelineState.RUNNING, TimelineKind.SCHEDULE).isAllDay)
    }

    // K2 绘制参数：全天日程不进弧段；细环取第一条全天日程
    @Test fun dialLayers() {
        val run = timed(10, "Run 5k", 900, 960, TimelineState.RUNNING)
        val layers = todayDialLayers(listOf(birthday, run, trip))
        assertEquals(listOf(run), layers.arcs)
        assertEquals(birthday, layers.allDayRing)
        assertNull(todayDialLayers(listOf(run)).allDayRing)
    }

    // K3 卡片状态行
    @Test fun rowStatus() {
        assertEquals("日程", todayRowStatus(birthday))
        assertEquals("计划 · 进行中", todayRowStatus(timed(10, "Run", 900, 960, TimelineState.RUNNING)))
        assertEquals("日程 · 待开始", todayRowStatus(timed(11, "会议", 1000, 1060, TimelineState.UPCOMING, TimelineKind.SCHEDULE)))
    }
}
