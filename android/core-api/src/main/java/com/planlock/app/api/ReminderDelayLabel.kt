package com.planlock.app.api

/**
 * REQ-044 U-R8 / U-R5 推迟时长的唯一写法：不足 60 分钟写分钟，60 分钟起写整小时（ColorOS 的三天推迟读作「72 小时」）。
 * 至少 1 分钟——低于 1 分钟不算推迟（判定阈值见 PlanAlarmLateness）。
 */
fun reminderDelayLabel(lateMs: Long): String {
    val minutes = (lateMs / 60_000L).coerceAtLeast(1)
    return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时"
}
