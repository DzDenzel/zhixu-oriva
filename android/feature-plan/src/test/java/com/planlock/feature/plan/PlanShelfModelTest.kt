package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanShelfModelTest {
    private val today = OccurrenceDate.parseIso("2026-10-16")!!

    private fun schedule(
        id: Long, title: String, start: String, end: String = start, allDay: Boolean = false,
        time: String? = "09:00", rule: String? = null, status: Int = 1,
    ) = ScheduleSnapshotEntity(id, 1, title, start, if (allDay) null else time, end, if (allDay) null else "10:00",
        allDay, null, null, null, rule, null, tzId = null, status = status, version = 1)

    /** BUG-105 复现：昨天的全天生日（每年）+ 过去 / 今天 / 未来跨月的一次性日程——数字 = 列表条数，每条都能找到。 */
    @Test fun countEqualsListAndEveryValidScheduleIsListed() {
        val rows = listOf(
            schedule(1, "某人生日", "2026-10-15", allDay = true, rule = """{"type":"yearly","month":10,"day":15}"""),
            schedule(2, "上周会议", "2026-10-09"),
            schedule(3, "九月复盘", "2026-09-30"),
            schedule(4, "今天体检", "2026-10-16", time = "15:00"),
            schedule(5, "下月旅行", "2026-11-02", "2026-11-05", allDay = true),
            schedule(6, "元旦", "2027-01-01", allDay = true),
            schedule(7, "停用的提醒", "2026-10-20", status = 0),
        )
        val shelf = scheduleShelf(rows, today)
        assertEquals(6, shelf.count)
        assertEquals(shelf.count, shelf.entries.size)
        assertEquals(setOf(1L, 2, 3, 4, 5, 6), shelf.entries.map { it.schedule.id }.toSet())
    }

    @Test fun repeatingScheduleAppearsOnceAtNextOccurrenceWithYear() {
        val shelf = scheduleShelf(listOf(schedule(1, "某人生日", "2026-10-15", allDay = true, rule = """{"type":"yearly","month":10,"day":15}""")), today)
        val entry = shelf.entries.single()
        assertEquals("2027-10-15", entry.date)
        assertFalse(entry.past)
        assertEquals("2027 年 10 月", shelf.groups.single().label)
    }

    @Test fun dailyRepeatIsOneEntryNotOnePerDay() {
        val shelf = scheduleShelf(listOf(schedule(1, "喝水", "2026-10-01", rule = """{"type":"daily"}""")), today)
        assertEquals(1, shelf.count)
        assertEquals("2026-10-16", shelf.entries.single().date)
    }

    @Test fun multipleAllDayOnSameDayAndMixedWithTimed() {
        val shelf = scheduleShelf(listOf(
            schedule(1, "会议", "2026-10-16", time = "14:00"),
            schedule(2, "生日 A", "2026-10-16", allDay = true),
            schedule(3, "生日 B", "2026-10-16", allDay = true),
            schedule(4, "早会", "2026-10-16", time = "08:30"),
        ), today)
        assertEquals(4, shelf.count)
        assertEquals(listOf(2L, 3, 4, 1), shelf.entries.map { it.schedule.id })
    }

    /** 负责人 2026-10-18：还没结束的在前（月份升序），已过去的排在最后（最近的在前）。 */
    @Test fun upcomingFirstThenPastMostRecentFirst() {
        val shelf = scheduleShelf(listOf(
            schedule(1, "九月", "2026-09-30"),
            schedule(2, "十月过去", "2026-10-02"),
            schedule(3, "十月将来", "2026-10-20"),
            schedule(4, "十一月", "2026-11-03"),
            schedule(5, "十月更早", "2026-10-01", time = "08:00"),
        ), today)
        assertEquals(listOf("2026-10", "2026-11"), shelf.upcoming.map { it.key })
        assertEquals(listOf("past-2026-10", "past-2026-09"), shelf.past.map { it.key })
        assertEquals(listOf(3L, 4, 2, 5, 1), shelf.entries.map { it.schedule.id })
        assertTrue(shelf.past.flatMap { it.entries }.all { it.past })
        assertFalse(shelf.upcoming.flatMap { it.entries }.any { it.past })
        assertEquals(3, shelf.pastCount)
        assertEquals(5, shelf.count)
        assertEquals("10 月", shelf.past.first().label)
    }

    /** 正在进行的跨天日程（开始于今天之前）算「还没结束」，排在前面。 */
    @Test fun ongoingIsInUpcomingSectionBeforeLaterOnes() {
        val shelf = scheduleShelf(listOf(
            schedule(1, "下周", "2026-10-23"),
            schedule(2, "出差", "2026-10-14", "2026-10-18", allDay = true),
            schedule(3, "上周", "2026-10-09"),
        ), today)
        assertEquals(listOf(2L, 1), shelf.upcoming.flatMap { it.entries }.map { it.schedule.id })
        assertEquals(listOf(3L), shelf.past.flatMap { it.entries }.map { it.schedule.id })
    }

    @Test fun ongoingMultiDayIsNotPast() {
        val e = scheduleShelf(listOf(schedule(1, "出差", "2026-10-14", "2026-10-18", allDay = true)), today).entries.single()
        assertFalse(e.past)
    }

    /** 已删除的行不在快照表里；同 ID 的重复行（如待确认冲突的本地副本被重复读入）只算一条。 */
    @Test fun duplicatesAndDisabledAreNotCounted() {
        val a = schedule(9, "冲突中的日程", "2026-10-18")
        val shelf = scheduleShelf(listOf(a, a.copy(title = "冲突中的日程（本地）"), schedule(10, "停用", "2026-10-18", status = 0)), today)
        assertEquals(1, shelf.count)
    }

    @Test fun emptyShelf() {
        val shelf = scheduleShelf(emptyList(), today)
        assertEquals(0, shelf.count)
        assertTrue(shelf.groups.isEmpty())
    }

    @Test fun planCountEqualsAllView() {
        fun plan(id: Long, status: Int = 1, start: String? = "09:00", end: String? = "10:00") = PlanSnapshotEntity(
            id = id, userId = 1, name = "P$id", type = "学习", startTime = start, endTime = end, repeatRule = null,
            color = null, icon = null, remindAdvanceMin = null, remindMethods = null, status = status, version = 1)
        val model = PlanOverview.build(listOf(plan(1), plan(2), plan(3, status = 0), plan(4, start = null), plan(1)), 600, today)
        assertEquals(4, planShelfCount(model))
    }
}
