package com.planlock.app

import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity

internal data class PlanNotificationText(val title: String, val body: String)

/**
 * Presentation only (REQ-044 U-R2 / U-R3 / U-R4 / U-R8); scheduling, scope ownership and delivery remain with the caller.
 * Public* strings are the lock-screen versions shown when the user hides sensitive notification content.
 */
internal object PlanNotificationCopy {
    const val ongoing = "正在按计划进行"
    const val publicWaiting = "知序 · 计划即将开始"
    const val publicStarted = "知序 · 计划开始了"
    const val publicRunning = "知序 · 计划进行中"
    const val publicCompleted = "知序 · 计划已完成"
    const val publicDeferred = "知序 · 提醒被系统推迟"

    fun waitingTitle(name: String, remainingMillis: Long): String =
        "「$name」${((remainingMillis + 59_999) / 60_000).coerceAtLeast(1)} 分钟后开始"

    fun startTitle(name: String) = "「$name」开始了"

    fun runningTitle(name: String) = "「$name」进行中"

    /** 「时段 · 类型」: the second line shared by every plan notification. */
    fun detail(startTime: String?, endTime: String?, typeLabel: String?): String =
        listOfNotNull(timeRange(startTime, endTime), typeLabel?.takeIf { it.isNotBlank() }).joinToString(" · ")

    fun completed(name: String, durationMillis: Long, detail: String) =
        PlanNotificationText("「$name」已完成 · 专注 ${durationLabel(durationMillis)}", detail)

    fun deferred(name: String, lateMs: Long) =
        PlanNotificationText("「$name」的提醒被系统推迟了 ${com.planlock.app.api.reminderDelayLabel(lateMs)}", "手机的后台策略推迟了提醒")

    /** U-R1 one-line hint, only when the plan itself silences the phone. */
    fun quietHint(autoDnd: Boolean, notifySilence: Boolean): String? = when {
        autoDnd && notifySilence -> "进行中会开启免打扰，并静默其他应用的通知"
        autoDnd -> "进行中会开启免打扰"
        notifySilence -> "进行中会静默其他应用的通知"
        else -> null
    }
}

internal suspend fun planNotificationDetail(store: RecordStore, plan: PlanSnapshotEntity): String =
    PlanNotificationCopy.detail(plan.startTime, plan.endTime, store.planTypeLabel(plan.type))

private fun clock(value: String?): String? = value?.takeIf { it.length >= 5 && it[2] == ':' }?.substring(0, 5)

internal fun timeRange(startTime: String?, endTime: String?): String? {
    val start = clock(startTime) ?: return null
    return clock(endTime)?.let { "$start–$it" } ?: start
}

internal fun durationLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when { minutes < 1 -> "不足 1 分钟"; minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"; else -> "${minutes / 60} 小时 ${minutes % 60} 分" }
}
