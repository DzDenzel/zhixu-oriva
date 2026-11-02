package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class PlanAlertSoundPolicyTest {
    @Test fun silentNeverSoundsOrVibrates() {
        for (filter in 1..4) for (owned in listOf(false, true)) {
            assertFalse(PlanAlertSoundPolicy.sound(0, filter, owned))
            assertFalse(PlanAlertSoundPolicy.vibrate(0, filter, owned))
        }
    }
    @Test fun vibrationModeOnlyVibratesOutsideUserDnd() {
        assertFalse(PlanAlertSoundPolicy.sound(1, 1, false))
        assertTrue(PlanAlertSoundPolicy.vibrate(1, 1, false))
        assertFalse(PlanAlertSoundPolicy.vibrate(1, 3, false))
        assertTrue(PlanAlertSoundPolicy.vibrate(1, 4, true))
    }
    @Test fun userDndAndUnverifiedReleaseAreSilent() {
        for (filter in 2..4) assertFalse(PlanAlertSoundPolicy.sound(2, filter, false))
        assertFalse(PlanAlertSoundPolicy.sound(2, 1, true, verified = false))
        assertFalse(PlanAlertSoundPolicy.vibrate(2, 1, true, verified = false))
    }
    @Test fun ownDndAllowsAlarmAndNormalModeAllowsCompletion() {
        assertTrue(PlanAlertSoundPolicy.sound(2, 4, true))
        assertTrue(PlanAlertSoundPolicy.sound(2, 1, false))
    }
    @Test fun snoozeRingsAgainOnlyWhileBeforeStart() {
        val start = 1_000_000_000L
        // 15 分钟前提醒 → 5 分钟后再提醒仍在开始前：再响一次
        assertEquals(start - 10 * 60_000L, PlanReminderSnooze.next(start - 15 * 60_000L, start))
        // 5 分钟后恰好到开始或已过开始：不推迟，交给开始闹钟
        assertNull(PlanReminderSnooze.next(start - 5 * 60_000L, start))
        assertNull(PlanReminderSnooze.next(start - 3 * 60_000L, start))
        // 开始前 5 分 1 秒仍可推迟一次
        assertEquals(start - 1_000L, PlanReminderSnooze.next(start - 5 * 60_000L - 1_000L, start))
    }
    @Test fun blockedSnoozeTellsWholeMinutesLeft() {
        assertEquals("再过 3 分钟计划就开始了，到时直接提醒你", PlanNotificationCopy.startsSoon(2 * 60_000L + 1))
        assertEquals("再过 1 分钟计划就开始了，到时直接提醒你", PlanNotificationCopy.startsSoon(5_000L))
    }
}
