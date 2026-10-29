package com.planlock.app.record

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanAlarmDeliveryPolicyTest {
    @Test fun enabledUsesClockForEveryMoment() {
        PlanAlarmKind.entries.forEach { assertEquals(PlanAlarmDeliveryMode.ALARM_CLOCK, planAlarmDeliveryMode(it, true, true)) }
    }
    @Test fun disabledRestoresOrdinaryExactForEveryKind() {
        PlanAlarmKind.entries.forEach { assertEquals(PlanAlarmDeliveryMode.EXACT, planAlarmDeliveryMode(it, false, true)) }
    }
    @Test fun alarmClockDoesNotRequireSpecialExactAccess() {
        PlanAlarmKind.entries.forEach {
            assertEquals(PlanAlarmDeliveryMode.ALARM_CLOCK, planAlarmDeliveryMode(it, true, false))
            assertEquals(PlanAlarmDeliveryMode.INEXACT, planAlarmDeliveryMode(it, false, false))
        }
    }
}
