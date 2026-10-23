package com.planlock.feature.plan

/**
 * BUG-125（设计侧裁决，2026-10-21）：保存时已进入本次的提前提醒窗口——本次不补发，下一次照常（调度归 codex，不变）。
 * 界面只负责把这件事说清楚：编辑页「提醒」下方实时显示一行，用户保存前就知道。
 *
 * @param startTime 计划开始时刻「HH:mm」
 * @param reminder 提前量（分钟），null / ≤ 0 = 不提醒
 * @param repeatDays 重复的星期（ISO：1 = 周一 … 7 = 周日）
 * @param nowMinutes 此刻是当天第几分钟
 * @param todayIsoDay 今天星期几（ISO）
 * @param nowMillisInMinute 当前这一分钟内已过的毫秒，保留刚错过提醒时刻的精度
 * @return 需要提示时的一句话；否则 null
 */
internal fun reminderWindowNote(startTime: String, reminder: Int?, repeatDays: Set<Int>, nowMinutes: Int, todayIsoDay: Int, nowMillisInMinute: Int = 0): String? {
    val lead = reminder?.takeIf { it > 0 } ?: return null
    val start = PlanTime.minutesOfDay(startTime) ?: return null
    if (todayIsoDay !in repeatDays) return null
    val remainingMillis = (start - nowMinutes) * 60_000L - nowMillisInMinute
    // Preserve exact equality, but don't treat the entire reminder minute as that instant.
    if (remainingMillis <= 0 || remainingMillis >= lead * 60_000L) return null
    val left = (remainingMillis + 59_999L) / 60_000L
    return "离今天开始只剩 $left 分钟，已过提醒时间，今天这次不再提醒，之后照常"
}
