package com.planlock.feature.plan

import com.planlock.app.record.SyncConfirmation
import com.planlock.app.record.PlanOccurrence

internal data class ConfirmationField(val label: String, val mine: String, val remote: String)

internal data class ConfirmationPresentation(
    val title: String,
    val explanation: String,
    val fields: List<ConfirmationField>,
    val keepRemoteLabel: String,
    val keepMineLabel: String,
)

internal fun SyncConfirmation.affectedPlanId(): Long? = if (kind == "plan") id else null

/** UI-only projection: neither side is mutated or silently preferred while the user compares it. */
internal fun SyncConfirmation.presentation(): ConfirmationPresentation {
    val isPlan = kind == "plan"
    val localTitle = if (isPlan) localPlan?.name else localSchedule?.title
    val remoteTitle = if (isPlan) remotePlan?.name else remoteSchedule?.title
    val title = localTitle?.takeIf { it.isNotBlank() } ?: remoteTitle?.takeIf { it.isNotBlank() }
        ?: if (isPlan) "执行设置 · 计划 $id" else "日程 $id"
    val fields = buildList<ConfirmationField> {
        fun field(label: String, mine: String?, remote: String?) {
            add(ConfirmationField(label, mine?.takeIf { it.isNotBlank() } ?: "未设置",
                if (remoteDeleted) "对方已删除" else remote?.takeIf { it.isNotBlank() } ?: "未设置"))
        }
        field("标题", if (deleting) localTitle?.let { "本机已删除\n删除前：$it" } ?: "本机已删除" else localTitle, remoteTitle)
        if (isPlan) {
            field("日期 / 重复", localPlan?.let { confirmationRepeat(it.repeatRule, legacyDaily = true) }, remotePlan?.let { confirmationRepeat(it.repeatRule, legacyDaily = true) })
            field("时间", confirmationTime(localPlan?.startTime, localPlan?.endTime), confirmationTime(remotePlan?.startTime, remotePlan?.endTime))
            field("执行状态", confirmationStatus(localPlan?.status), confirmationStatus(remotePlan?.status))
            field("限制应用", parseAppNames(localStrategy?.blacklistApps).joinToString("、"), parseAppNames(remoteStrategy?.blacklistApps).joinToString("、"))
            field("不计分心应用", parseAppNames(localStrategy?.whitelistApps).joinToString("、"), parseAppNames(remoteStrategy?.whitelistApps).joinToString("、"))
            field("免打扰", confirmationSwitch(localStrategy?.autoDnd), confirmationSwitch(remoteStrategy?.autoDnd))
            field("通知静默", confirmationSwitch(localStrategy?.notifySilence), confirmationSwitch(remoteStrategy?.notifySilence))
            field("提醒", confirmationReminder(localPlan?.remindAdvanceMin), confirmationReminder(remotePlan?.remindAdvanceMin))
        } else {
            field("日期", confirmationDates(localSchedule?.startDate, localSchedule?.endDate), confirmationDates(remoteSchedule?.startDate, remoteSchedule?.endDate))
            field("时间", if (localSchedule?.allDay == true) "全天" else confirmationTime(localSchedule?.startTime, localSchedule?.endTime),
                if (remoteSchedule?.allDay == true) "全天" else confirmationTime(remoteSchedule?.startTime, remoteSchedule?.endTime))
            field("重复", localSchedule?.let { confirmationRepeat(it.repeatRule) }, remoteSchedule?.let { confirmationRepeat(it.repeatRule) })
            field("地点", localSchedule?.location, remoteSchedule?.location)
            field("备注", localSchedule?.note, remoteSchedule?.note)
            field("提醒", confirmationReminder(localSchedule?.remindAdvanceMin), confirmationReminder(remoteSchedule?.remindAdvanceMin))
            field("时区", localSchedule?.tzId, remoteSchedule?.tzId)
            field("关联计划", localSchedule?.linkedPlanId?.let { "计划 $it" }, remoteSchedule?.linkedPlanId?.let { "计划 $it" })
        }
    }
    return ConfirmationPresentation(
        title = title,
        explanation = when {
            deleting -> "对方修改过，仍要删除吗？"
            remoteDeleted -> "对方已删除这条记录。你可以接受删除，或恢复我的版本并重新同步。"
            else -> "这条记录在两台设备上有不同修改。请比较两边内容，再选择要保留的版本。"
        },
        fields = fields,
        keepRemoteLabel = if (remoteDeleted) "接受删除" else "保留对方版本",
        keepMineLabel = if (remoteDeleted) "恢复我的版本" else "保留我的版本",
    )
}

private fun confirmationStatus(value: Int?): String = when (value) {
    1 -> "已启用"
    0 -> "已暂停"
    null -> "未设置"
    else -> "状态 $value"
}

private fun confirmationSwitch(value: Boolean?): String = when (value) {
    true -> "开启"
    false -> "关闭"
    null -> "未设置"
}

private fun confirmationReminder(value: Int?): String = when {
    value == null -> "未设置"
    value == 0 -> "开始时提醒"
    else -> "提前 $value 分钟"
}

private fun confirmationDates(start: String?, end: String?): String = when {
    start.isNullOrBlank() -> "未设置"
    end.isNullOrBlank() || start == end -> start
    else -> "$start 至 $end"
}

private fun confirmationTime(start: String?, end: String?): String {
    if (start.isNullOrBlank() && end.isNullOrBlank()) return "未设置"
    val startMinute = PlanTime.minutesOfDay(start)
    val endMinute = PlanTime.minutesOfDay(end)
    val overnight = startMinute != null && endMinute != null && endMinute < startMinute
    return "${PlanTime.format(start)} 至 ${PlanTime.format(end)}" + if (overnight) "（跨天）" else ""
}

/** Retain unknown rules visibly rather than making different custom recurrences look identical. */
private fun confirmationRepeat(raw: String?, legacyDaily: Boolean = false): String {
    if (raw.isNullOrBlank()) return if (legacyDaily) "每天" else "不重复"
    if (!PlanOccurrence.isSupportedRule(raw)) return "自定义：$raw"
    fun intField(key: String) = Regex("\"$key\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    return when (Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)) {
        "daily" -> "每天"
        "weekly" -> {
            val days = Regex("\"weekdays\"\\s*:\\s*\\[([^]]*)]").find(raw)?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            if (days.isNullOrEmpty() || days.any { it !in 1..7 }) "自定义：$raw"
            else "每周 " + days.distinct().sorted().joinToString("、") { "周${listOf("一", "二", "三", "四", "五", "六", "日")[it - 1]}" }
        }
        "monthly" -> intField("day")?.takeIf { it in 1..31 }?.let { "每月 $it 日" } ?: "自定义：$raw"
        "yearly" -> {
            val month = intField("month")?.takeIf { it in 1..12 }
            val day = intField("day")?.takeIf { it in 1..31 }
            if (month != null && day != null) "每年 $month 月 $day 日" else "自定义：$raw"
        }
        else -> "自定义：$raw"
    }
}
