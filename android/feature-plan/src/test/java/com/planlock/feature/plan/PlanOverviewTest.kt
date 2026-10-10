package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.OccurrenceDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanOverviewTest {
    private fun plan(
        id: Long,
        name: String,
        type: String,
        start: String? = "09:00:00",
        end: String? = "10:00:00",
        repeat: String? = "{\"type\":\"daily\"}",
        status: Int = 1,
    ) = PlanSnapshotEntity(id, 1, name, type, start, end, repeat, null, null, 15, null, status, 0)

    @Test
    fun `default projection assigns each plan once and finds next occurrence`() {
        val active = plan(1, "正在专注", "学习")
        val laterToday = plan(2, "晚间阅读", "学习", "20:00:00", "21:00:00")
        val friday = plan(3, "周五复盘", "工作", repeat = "{\"type\":\"weekly\",\"weekdays\":[5]}")
        val paused = plan(4, "暂停项目", "生活", status = 0)
        val incomplete = plan(5, "待排时间", "健康", start = null)

        val result = PlanOverview.build(listOf(active, laterToday, friday, paused, incomplete), nowMinutes = 9 * 60 + 30, today = OccurrenceDate(2026, 9, 29, 2))

        assertEquals(listOf(1L), result.current.map { it.id })
        assertEquals(listOf(2L, 3L), result.upcoming.map { it.plan.id })
        assertEquals(listOf(0, 3), result.upcoming.map { it.dayOffset })
        assertEquals(setOf(4L, 5L), result.stored.map { it.id }.toSet())
        assertEquals(3, result.scheduled.size)
        assertEquals(2, result.categoryCounts["学习"])
        assertEquals(1, result.categoryCounts["工作"])

        val defaultIds = result.current.map { it.id } + result.upcoming.map { it.plan.id }
        assertEquals(defaultIds.distinct(), defaultIds)
        assertFalse(result.categoryCounts.containsKey("生活"))
        assertFalse(result.categoryCounts.containsKey("健康"))
    }

    @Test
    fun `weekly next occurrence wraps inside seven day window`() {
        val monday = plan(9, "下周一", "工作", repeat = "{\"type\":\"weekly\",\"weekdays\":[1]}")
        val result = PlanOverview.build(listOf(monday), nowMinutes = 12 * 60, today = OccurrenceDate(2026, 9, 27, 7))

        assertEquals(1, result.upcoming.single().dayOffset)
        assertTrue(result.current.isEmpty())
    }
}
