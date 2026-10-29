package com.planlock.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanNotificationCopyTest {

    @Test fun waitingTitleUsesTheActualRemainingTime() {
        assertEquals("「晨读」1 分钟后开始", PlanNotificationCopy.waitingTitle("晨读", 60_000))
        assertEquals("「晨读」15 分钟后开始", PlanNotificationCopy.waitingTitle("晨读", 900_000))
    }

    @Test fun waitingTitleRoundsUpUntilTheNextMinute() {
        assertEquals("「晨读」2 分钟后开始", PlanNotificationCopy.waitingTitle("晨读", 60_001))
        assertEquals("「晨读」1 分钟后开始", PlanNotificationCopy.waitingTitle("晨读", 1))
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
