package com.planlock.app.record

enum class PlanAlarmDeliveryMode { ALARM_CLOCK, EXACT, INEXACT }

fun planAlarmDeliveryMode(@Suppress("UNUSED_PARAMETER") kind: PlanAlarmKind, punctualStart: Boolean, exactAllowed: Boolean): PlanAlarmDeliveryMode = when {
    punctualStart -> PlanAlarmDeliveryMode.ALARM_CLOCK
    exactAllowed -> PlanAlarmDeliveryMode.EXACT
    else -> PlanAlarmDeliveryMode.INEXACT
}
