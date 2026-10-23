package com.planlock.app.record

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanAlarmDeliveryPolicyTest {
    @Test fun enabledUsesClockForStartAndReminderOnly() {
        assertEquals(PlanAlarmDeliveryMode.ALARM_CLOCK, planAlarmDeliveryMode(PlanAlarmKind.START, true, true))
        assertEquals(PlanAlarmDeliveryMode.ALARM_CLOCK, planAlarmDeliveryMode(PlanAlarmKind.REMINDER, true, true))
        assertEquals(PlanAlarmDeliveryMode.EXACT, planAlarmDeliveryMode(PlanAlarmKind.END, true, true))
    }
    @Test fun disabledRestoresOrdinaryExactForEveryKind() {
        PlanAlarmKind.entries.forEach { assertEquals(PlanAlarmDeliveryMode.EXACT, planAlarmDeliveryMode(it, false, true)) }
    }
    @Test fun permissionRevocationAlwaysDegradesWithoutClock() {
        for (enabled in listOf(true, false)) PlanAlarmKind.entries.forEach {
            assertEquals(PlanAlarmDeliveryMode.INEXACT, planAlarmDeliveryMode(it, enabled, false))
        }
    }
}
