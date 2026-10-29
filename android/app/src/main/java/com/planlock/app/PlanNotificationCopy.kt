package com.planlock.app

internal data class PlanNotificationText(val title: String, val body: String)

/** Presentation only; scheduling, scope ownership and delivery remain with the caller. */
internal object PlanNotificationCopy {
    const val ongoing = "正在按计划进行"

    fun waitingTitle(name: String, remainingMillis: Long): String =
        "「$name」${((remainingMillis + 59_999) / 60_000).coerceAtLeast(1)} 分钟后开始"

    fun completed(name: String, durationMillis: Long) =
        PlanNotificationText("「$name」已完成", "专注 ${durationLabel(durationMillis)}")
}

internal fun durationLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when { minutes < 1 -> "不足 1 分钟"; minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"; else -> "${minutes / 60} 小时 ${minutes % 60} 分" }
}
