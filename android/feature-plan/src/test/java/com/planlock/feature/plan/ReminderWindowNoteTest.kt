package com.planlock.feature.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderWindowNoteTest {
    private val weekdays = setOf(1, 2, 3, 4, 5)

    @Test fun firstMillisecondAndRestOfReminderMinuteAreAlreadyPastTheTrigger() {
        for (fraction in listOf(1, 15_000, 59_999)) {
            assertEquals("离今天开始只剩 15 分钟，已过提醒时间，今天这次不再提醒，之后照常",
                reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 45, 3, fraction))
        }
    }

    @Test fun remainingMinutesRoundUpAndTheStartInstantDoesNotWarn() {
        assertEquals("离今天开始只剩 1 分钟，已过提醒时间，今天这次不再提醒，之后照常",
            reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 59, 3, 59_999))
        assertNull(reminderWindowNote("21:00", 15, weekdays, 21 * 60, 3, 0))
        assertNull(reminderWindowNote("21:00", 15, weekdays, 21 * 60, 3, 1))
    }

    @Test fun insideWindowExplainsThisTimeIsSkipped() {
        // 21:00 开始、提前 15 分钟，此刻 20:52（周三）
        assertEquals("离今天开始只剩 8 分钟，已过提醒时间，今天这次不再提醒，之后照常",
            reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 52, 3))
    }

    @Test fun beforeWindowOrExactlyOnTimeSaysNothing() {
        assertNull(reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 30, 3))
        assertNull(reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 45, 3))
    }

    @Test fun startedNoReminderOrNotTodaySaysNothing() {
        assertNull(reminderWindowNote("21:00", 15, weekdays, 21 * 60, 3))
        assertNull(reminderWindowNote("21:00", 15, weekdays, 21 * 60 + 5, 3))
        assertNull(reminderWindowNote("21:00", null, weekdays, 20 * 60 + 52, 3))
        assertNull(reminderWindowNote("21:00", 15, weekdays, 20 * 60 + 52, 6))
    }
}
