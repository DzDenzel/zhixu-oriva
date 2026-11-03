package com.planlock.feature.settings

import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.AlarmKind
import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import com.planlock.app.api.ReadinessState.OFF
import com.planlock.app.api.ReadinessState.ON
import com.planlock.app.api.ReadinessState.UNKNOWN
import com.planlock.app.api.ReminderReadiness
import com.planlock.app.api.ReminderReadinessItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderGuideModelTest {
    private fun readiness(overrides: Map<ReadinessKey, ReadinessState> = emptyMap(), deferral: AlarmDeferral? = null) =
        ReminderReadiness(ReadinessKey.entries.map { ReminderReadinessItem(it, overrides[it] ?: ON, null) }, deferral)

    @Test fun allOnIsReadyAndOnlyEssentialRowsShow() {
        val model = reminderGuideModel(readiness())
        assertEquals(ReminderGuideVerdict.READY, model.verdict)
        assertEquals(listOf(ReadinessKey.NOTIFICATIONS, ReadinessKey.FULL_SCREEN, ReadinessKey.BACKGROUND, ReadinessKey.VENDOR_NOTIFICATION),
            model.rows.map { it.key })
        assertEquals("提醒已就绪", verdictTitle(model))
    }

    @Test fun colorOsUnreadableItemsAskForSelfCheckNotReady() {
        val model = reminderGuideModel(readiness(mapOf(ReadinessKey.VENDOR_NOTIFICATION to UNKNOWN, ReadinessKey.BACKGROUND to UNKNOWN)))
        assertEquals(ReminderGuideVerdict.SELF_CHECK, model.verdict)
        assertEquals(0, model.missing)
    }

    @Test fun usuallyOnItemsAppearOnlyWhenOff() {
        val model = reminderGuideModel(readiness(mapOf(ReadinessKey.EXACT_ALARM to OFF, ReadinessKey.PROMOTED to UNKNOWN)))
        assertTrue(model.rows.any { it.key == ReadinessKey.EXACT_ALARM })
        assertFalse(model.rows.any { it.key == ReadinessKey.PROMOTED })
        assertEquals(ReminderGuideVerdict.NEEDS_ACTION, model.verdict)
        assertEquals("还差 1 项", verdictTitle(model))
    }

    @Test fun calendarIsASwitchNotAChecklistRow() {
        val model = reminderGuideModel(readiness(mapOf(ReadinessKey.CALENDAR_FALLBACK to OFF)))
        assertFalse(model.calendarOn)
        assertFalse(model.rows.any { it.key == ReadinessKey.CALENDAR_FALLBACK })
        assertEquals(ReminderGuideVerdict.READY, model.verdict)
    }

    @Test fun deferralPutsBackgroundFirstAndStatesTheDelay() {
        val deferral = AlarmDeferral(AlarmKind.START, 7, "晨读", scheduledAt = 0, deliveredAt = 259_200_000)
        val model = reminderGuideModel(readiness(mapOf(ReadinessKey.BACKGROUND to UNKNOWN), deferral))
        assertEquals(ReadinessKey.BACKGROUND, model.rows.first().key)
        assertEquals("上次提醒被系统推迟了 72 小时", model.deferralHeader)
        assertEquals("晨读", model.deferralPlan)
        assertNull(reminderGuideModel(readiness()).deferralHeader)
    }

    @Test fun deferralHeaderSwitchesToHoursAtSixtyMinutes() {
        fun header(lateMs: Long) = reminderGuideModel(readiness(deferral = AlarmDeferral(AlarmKind.START, 1, "x", 0, lateMs))).deferralHeader
        assertEquals("上次提醒被系统推迟了 59 分钟", header(59 * 60_000L + 59_999))
        assertEquals("上次提醒被系统推迟了 1 小时", header(60 * 60_000L))
        assertEquals("上次提醒被系统推迟了 1 小时", header(61 * 60_000L))
    }

    @Test fun colorOsPutsCalendarFirstAndRecommendsItUntilOn() {
        val off = reminderGuideModel(readiness(mapOf(ReadinessKey.CALENDAR_FALLBACK to OFF)), colorOs = true)
        assertTrue(off.calendarFirst)
        assertEquals("推荐：这台手机会推迟应用的提醒，系统日历能按时提醒", off.calendarRecommendation)
        val on = reminderGuideModel(readiness(), colorOs = true)
        assertTrue(on.calendarFirst)
        assertNull(on.calendarRecommendation)
        val other = reminderGuideModel(readiness(mapOf(ReadinessKey.CALENDAR_FALLBACK to OFF)), colorOs = false)
        assertFalse(other.calendarFirst)
        assertNull(other.calendarRecommendation)
    }

    @Test fun colorOsManufacturersAreRecognised() {
        listOf("OPPO", "OnePlus", " realme ").forEach { assertTrue(isColorOsManufacturer(it)) }
        listOf("Google", "Xiaomi", "samsung").forEach { assertFalse(isColorOsManufacturer(it)) }
    }
}
