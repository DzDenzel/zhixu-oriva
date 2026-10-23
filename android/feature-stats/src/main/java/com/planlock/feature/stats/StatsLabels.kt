package com.planlock.feature.stats

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/*
 * 统计页给人看的说法（UI 审查 2026-10-18 P1，BUG-084 口径）：不把 ISO 时间、方法代码、秒级时长直接摆给用户。
 * 只做展示投影，不改 reportDurationLabel（分心墨块与报告契约仍是「时:分:秒」）。
 */

/** 提前结束一行：「10 月 13 日 18:02  主动结束」；不在今年时带年份；时间读不出来时写「时间未知」。 */
internal fun earlyEndingLine(at: String?, method: String?, currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)): String =
    "${earlyEndingTime(at, currentYear)}  ${earlyEndingMethodLabel(method)}"

internal fun earlyEndingMethodLabel(method: String?): String = when (method?.trim()?.lowercase(Locale.ROOT)) {
    null, "", "user_ended" -> "主动结束"
    "backup_password", "password" -> "输入备用密码后结束"
    else -> "提前结束"
}

private val isoPattern = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})""")
private val zoneSuffix = Regex("""(Z|[+-]\d{2}:?\d{2})$""")

internal fun earlyEndingTime(at: String?, currentYear: Int): String {
    val raw = at?.trim().orEmpty()
    var parts = isoPattern.find(raw)?.groupValues?.drop(1)?.map { it.toInt() } ?: return "时间未知"
    // 带时区的（服务端 UTC）换算成本机时间；不带时区的本来就是本机时间。
    if (zoneSuffix.containsMatchIn(raw)) {
        val normalized = raw.replace(Regex("""\.\d+"""), "").replace("Z", "+00:00")
        val parsed = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ROOT).parse(normalized) }.getOrNull()
        }
        if (parsed != null) {
            val c = Calendar.getInstance().apply { time = parsed }
            parts = listOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
    }
    val (year, month, day, hour, minute) = parts
    val date = if (year == currentYear) "$month 月 $day 日" else "$year 年 $month 月 $day 日"
    return "%s %02d:%02d".format(Locale.ROOT, date, hour, minute)
}

/**
 * 完成情况里的计划 / 实际时长：「45 分钟」「2 小时 5 分」「约 168 小时」。
 * 十小时以上只保留整小时（这一行比的是量级，不是秒表）。
 */
internal fun planDurationLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    return when {
        minutes < 60 -> "$minutes 分钟"
        hours < 10 -> if (minutes % 60 == 0L) "$hours 小时" else "$hours 小时 ${minutes % 60} 分"
        else -> "约 ${(minutes + 30) / 60} 小时"
    }
}
