package com.planlock.app

import org.junit.Assert.assertEquals
import org.junit.Test
import com.planlock.app.record.entity.PlanSnapshotEntity

class PlanNotificationCopyTest {
    private val plan = PlanSnapshotEntity(-1, 0, "晨读", "custom:reading", "08:20", "08:22", null, null, null, 1, null, 1, 0)

    @Test fun reminderUsesPlanTitleTimeAndResolvedTypeName() {
        val copy = PlanNotificationCopy.reminder(plan, "练琴")
        assertEquals("「晨读」1 分钟后开始", copy.title)
        assertEquals("08:20 – 08:22 · 练琴", copy.body)
    }

    @Test fun overnightReminderMakesNextDayExplicit() {
        val copy = PlanNotificationCopy.reminder(plan.copy(startTime = "23:30", endTime = "01:00", remindAdvanceMin = 15), "学习")
        assertEquals("「晨读」15 分钟后开始", copy.title)
        assertEquals("23:30 – 次日 01:00 · 学习", copy.body)
    }

    @Test fun completionDoesNotRepeatItsTitleInTheBody() {
        val copy = PlanNotificationCopy.completed("晨读", 120_000)
        assertEquals("「晨读」已完成", copy.title)
        assertEquals("专注 2 分钟", copy.body)
        assertEquals("正在按计划进行", PlanNotificationCopy.ongoing)
    }

    @Test fun completionDurationUsesReadableUnits() {
        assertEquals("不足 1 分钟", durationLabel(20_000))
        assertEquals("5 分钟", durationLabel(300_000))
        assertEquals("1 小时", durationLabel(3_600_000))
        assertEquals("1 小时 20 分", durationLabel(4_800_000))
    }
}
