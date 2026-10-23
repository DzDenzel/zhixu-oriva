package com.planlock.feature.plan

import java.util.Calendar

/**
 * §7.9 设计裁决（v4.6）：计划详情在「今天已完成 / 今天已结束」时补一行下一次时间。
 * 下一次由 codex 按重复规则算好传入（[next] 为空 = 没有下一次，整行不显示）；这里只负责写成人话：
 * 「下一次：明天 06:00」「下一次：周一 06:00」（一周内）「下一次：10 月 28 日 06:00」（更远）。
 */
internal fun nextOccurrenceLabel(next: Calendar?, now: Calendar): String? {
    next ?: return null
    val time = "%02d:%02d".format(next.get(Calendar.HOUR_OF_DAY), next.get(Calendar.MINUTE))
    val days = dayIndex(next) - dayIndex(now)
    val day = when {
        days < 0 -> return null
        days == 0L -> "今天"
        days == 1L -> "明天"
        days in 2..6 -> "周" + "日一二三四五六"[next.get(Calendar.DAY_OF_WEEK) - 1]
        else -> "${next.get(Calendar.MONTH) + 1} 月 ${next.get(Calendar.DAY_OF_MONTH)} 日"
    }
    return "下一次：$day $time"
}

/** 按当地日历日计数（不受时刻与夏令时影响）。 */
private fun dayIndex(c: Calendar): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    utc.clear()
    utc.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    return utc.timeInMillis / 86_400_000L
}
