package com.planlock.app.record

enum class PlanAlarmDeliveryMode { ALARM_CLOCK, EXACT, INEXACT }

fun planAlarmDeliveryMode(kind: PlanAlarmKind, punctualStart: Boolean, exactAllowed: Boolean): PlanAlarmDeliveryMode = when {
    !exactAllowed -> PlanAlarmDeliveryMode.INEXACT
    punctualStart && kind != PlanAlarmKind.END -> PlanAlarmDeliveryMode.ALARM_CLOCK
    else -> PlanAlarmDeliveryMode.EXACT
}
