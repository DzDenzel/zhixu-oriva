package com.planlock.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanNotificationCopyTest {

    @Test fun waitingTitleCarriesNoMinutesThatCouldGoStale() {
        assertEquals("「晨读」即将开始", PlanNotificationCopy.waitingTitle("晨读"))
    }

    @Test fun detailLineIsTimeRangeThenType() {
        assertEquals("07:00–07:45 · 阅读", PlanNotificationCopy.detail("07:00:00", "07:45:00", "阅读"))
        assertEquals("23:30–00:30 · 休息", PlanNotificationCopy.detail("23:30:00", "00:30:00", "休息"))
        assertEquals("07:00 · 阅读", PlanNotificationCopy.detail("07:00:00", null, "阅读"))
        assertEquals("阅读", PlanNotificationCopy.detail(null, null, "阅读"))
        assertEquals("07:00–07:45", PlanNotificationCopy.detail("07:00", "07:45", " "))
    }

    @Test fun completionPutsFocusTimeInTheTitle() {
        val copy = PlanNotificationCopy.completed("专注阅读", 45 * 60_000L, "07:00–07:45 · 阅读")
        assertEquals("「专注阅读」已完成 · 专注 45 分钟", copy.title)
        assertEquals("07:00–07:45 · 阅读", copy.body)
    }

    @Test fun completionDurationUsesReadableUnits() {
        assertEquals("不足 1 分钟", durationLabel(20_000))
        assertEquals("5 分钟", durationLabel(300_000))
        assertEquals("1 小时", durationLabel(3_600_000))
        assertEquals("1 小时 20 分", durationLabel(4_800_000))
    }

    @Test fun deferralSwitchesToHoursFromSixtyMinutes() {
        assertEquals("「晨读」的提醒被系统推迟了 59 分钟", PlanNotificationCopy.deferred("晨读", 59 * 60_000L + 59_999).title)
        assertEquals("「晨读」的提醒被系统推迟了 1 小时", PlanNotificationCopy.deferred("晨读", 60 * 60_000L).title)
        assertEquals("「晨读」的提醒被系统推迟了 1 小时", PlanNotificationCopy.deferred("晨读", 61 * 60_000L).title)
        assertEquals("「晨读」的提醒被系统推迟了 72 小时", PlanNotificationCopy.deferred("晨读", 259_200_000L).title)
        assertEquals("「晨读」的提醒被系统推迟了 1 分钟", PlanNotificationCopy.deferred("晨读", 61_000L).title)
        assertEquals("手机的后台策略推迟了提醒", PlanNotificationCopy.deferred("晨读", 120_000L).body)
    }

    @Test fun quietHintOnlyWhenThePlanSilencesThePhone() {
        assertEquals(null, PlanNotificationCopy.quietHint(autoDnd = false, notifySilence = false))
        assertEquals("进行中会开启免打扰", PlanNotificationCopy.quietHint(autoDnd = true, notifySilence = false))
        assertEquals("进行中会静默其他应用的通知", PlanNotificationCopy.quietHint(autoDnd = false, notifySilence = true))
        assertEquals("进行中会开启免打扰，并静默其他应用的通知", PlanNotificationCopy.quietHint(autoDnd = true, notifySilence = true))
    }
}
