package com.planlock.app.api

import com.planlock.app.api.dto.StatsResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class AssistantScheduleDraft(
    val title: String,
    val startDate: String?,
    val allDay: Boolean = true,
    val startTime: String? = null,
    val endTime: String? = null,
    val repeatType: String? = null,
    val endDate: String? = null,
    val needsTimeConfirmation: Boolean = false,
)

data class AssistantPlanState(val id: Long, val name: String, val enabled: Boolean, val inWindow: Boolean, val remainingMinutes: Int)

/** A read-only, user-scoped snapshot. Capability handlers cannot perform I/O. */
data class AssistantSnapshot(
    val today: String,
    val arrangements: List<String> = emptyList(),
    val plans: List<AssistantPlanState> = emptyList(),
    val continuousDays: Int? = null,
    val reports: Map<String, StatsResponse> = emptyMap(),
    val todayUnlocks: Int? = null,
)

enum class AssistantDestination { TODAY, PLANS, STATS }

sealed interface AssistantAction {
    val label: String
    data class OpenPage(override val label: String, val destination: AssistantDestination) : AssistantAction
    data class EditSchedule(val draft: AssistantScheduleDraft, override val label: String = "确认日程草稿") : AssistantAction
    data class ChangePlan(override val label: String, val planId: Long, val enabled: Boolean) : AssistantAction
}

data class AssistantReply(val text: String, val actions: List<AssistantAction> = emptyList())

data class AssistantRequest(
    val text: String,
    val period: String = "week",
    val draft: AssistantScheduleDraft? = null,
    val enabled: Boolean? = null,
    val planName: String? = null,
)

data class AssistantCapability(
    val id: String,
    val title: String,
    val description: String,
    val example: String,
    val parse: (String, String) -> AssistantRequest?,
    val handler: (AssistantRequest, AssistantSnapshot) -> AssistantReply,
    val priority: Int = 0,
)

object AssistantCapabilityRegistry {
    val entries = listOf(
        AssistantCapability("schedule_draft", "记一件事", "先生成日程草稿，确认后保存", "记一下明天是某人生日",
            parse = { text, today -> if (Regex("^(记一下|记下|记住|记录|提醒我|帮我记)").containsMatchIn(text)) AssistantRequest(text, draft = parseAssistantDraft(text, today)) else null },
            handler = { request, _ ->
                val draft = checkNotNull(request.draft)
                AssistantReply(if (draft.startDate == null) "已整理标题，但日期尚未确定。请在日程表单填写日期，保存后才会记入日程。"
                    else "已整理日程草稿：${draft.title} · ${draft.startDate}${if (draft.allDay) " · 全天" else if (draft.needsTimeConfirmation) " · 时刻待填写" else " · ${draft.startTime}–${if (draft.endDate != draft.startDate) "次日 " else ""}${draft.endTime}"}${if (draft.repeatType == "yearly") " · 每年" else ""}。请核对后保存。",
                    listOf(AssistantAction.EditSchedule(draft)))
            }),
        AssistantCapability("today", "今日安排", "查看今天的日程、计划和当前行动", "今天有什么安排",
            parse = { text, _ -> if (("今日" in text || "今天" in text) && ("安排" in text || "日程" in text || "计划" in text) || "当前计划" in text && ("剩" in text || "多久" in text)) AssistantRequest(text) else null },
            handler = { request, snapshot ->
                val text = if ("当前计划" in request.text && ("剩" in request.text || "多久" in request.text)) {
                    snapshot.plans.filter { it.enabled && it.inWindow }.joinToString("\n") {
                        "${it.name} 还剩 %02d:%02d。".format(Locale.ROOT, it.remainingMinutes / 60, it.remainingMinutes % 60)
                    }.ifEmpty { "当前没有正在执行的计划。" }
                } else snapshot.arrangements.joinToString("\n").let { if (it.isEmpty()) "今天还没有日程或已启用计划。" else "今日安排（${snapshot.today}）：\n$it" }
                AssistantReply(text, listOf(AssistantAction.OpenPage("查看今日安排", AssistantDestination.TODAY)))
            }),
        AssistantCapability("streak", "连续达成", "查询最近保存的连续完成天数", "连续达成几天",
            parse = { text, _ -> if ("连续" in text && ("几天" in text || "达成" in text || "完成" in text)) AssistantRequest(text) else null },
            handler = { _, snapshot -> AssistantReply(snapshot.continuousDays?.let { "当前连续达成 $it 天（最近保存的报告）。" } ?: "暂无报告数据，请前往执行报告查看同步状态。",
                listOf(AssistantAction.OpenPage("查看执行报告", AssistantDestination.STATS))) }),
        AssistantCapability("report", "分心与时长", "查询今日、本周或本月的次数、时长和来源", "这周分心多久",
            parse = { text, _ -> if (listOf("分心", "违规", "解锁").any { it in text }) AssistantRequest(text, period = assistantPeriod(text)) else null },
            handler = { request, snapshot ->
                val report = snapshot.reports[request.period]
                val result = if ("解锁" in request.text) snapshot.todayUnlocks?.let { "今天已记录 $it 次解锁。当前版本没有设置每日次数上限。" }
                else report?.let {
                    val top = it.violationByApp.take(3).joinToString("、") { app ->
                        "${app.appName?.takeIf(String::isNotBlank) ?: app.packageName} ${assistantDuration(app.totalDurationMs)}${if (app.approxDurationMs > 0) "（含近似）" else ""}"
                    }.ifBlank { "暂无分心来源" }
                    "${periodLabel(request.period)}分心 ${it.violationCount} 次，累计 ${assistantDuration(it.violationDurationMs)}（时:分:秒）。\n来源：$top\n数据来自最近保存的执行报告。"
                }
                AssistantReply(result ?: "暂无报告数据，请前往执行报告查看同步状态。", listOf(AssistantAction.OpenPage("查看执行报告", AssistantDestination.STATS)))
            }),
        AssistantCapability("plan_control", "开始 / 结束计划", "选择当前时段的计划，确认后开始或结束", "结束当前计划",
            parse = { text, _ ->
                Regex("^(开始|恢复|结束|暂停|停止)(.*?)(计划|行动)$").matchEntire(text)?.let {
                    AssistantRequest(text, enabled = it.groupValues[1] in listOf("开始", "恢复"), planName = it.groupValues[2].takeUnless { value -> value in listOf("", "当前", "今日", "今天", "今天的", "今日的") })
                }
            }, handler = ::planControlReply, priority = 100),
    ).sortedByDescending { it.priority }

    fun respond(input: String, snapshot: AssistantSnapshot): AssistantReply {
        val text = input.trim().replace(Regex("\\s+"), "").trimEnd('。', '？', '?', '！', '!')
        for (capability in entries) {
            capability.parse(text, snapshot.today)?.let { return capability.handler(it, snapshot) }
        }
        val draft = parseAssistantDraft(text, snapshot.today)
        val actions = buildList {
            if (draft.startDate != null) add(AssistantAction.EditSchedule(draft, "将这件事整理为日程"))
            add(AssistantAction.OpenPage("查看今日安排", AssistantDestination.TODAY))
            add(AssistantAction.OpenPage("查看执行报告", AssistantDestination.STATS))
            if (draft.startDate == null) add(AssistantAction.EditSchedule(draft, "新建日程，填写日期"))
        }
        return AssistantReply("可以试试“今天有什么安排”“这周分心多久”或“记一下明天看展”。也可以直接打开下方入口。", actions)
    }
}

private fun planControlReply(request: AssistantRequest, snapshot: AssistantSnapshot): AssistantReply {
    val enabled = checkNotNull(request.enabled)
    val matching = snapshot.plans.filter { plan -> request.planName?.let { it == plan.name.replace(Regex("\\s+"), "") } ?: plan.inWindow }
    val actionable = matching.filter { it.inWindow && it.enabled != enabled }
    val target = actionable.singleOrNull()
    if (target != null) {
        val verb = if (enabled) "开始" else "结束"
        return AssistantReply("请确认$verb“${target.name}”。${if (!enabled) "结束后会暂停该计划。" else "将恢复当前时段的执行。"}",
            listOf(AssistantAction.ChangePlan("$verb ${target.name}", target.id, enabled)))
    }
    val reason = when {
        actionable.size > 1 -> "当前有多个符合条件的计划，请输入完整计划名称或前往计划页选择。"
        matching.size == 1 && matching.single().inWindow -> "“${matching.single().name}”${if (enabled) "已在执行" else "已结束"}，无需重复操作。"
        else -> "当前时段没有可${if (enabled) "开始" else "结束"}的匹配计划，请前往计划页选择。"
    }
    return AssistantReply(reason, listOf(AssistantAction.OpenPage("查看计划", AssistantDestination.PLANS)))
}

private fun assistantPeriod(text: String): String = when {
    "今天" in text || "今日" in text -> "day"
    "本月" in text || "这月" in text || "这个月" in text -> "month"
    else -> "week"
}

private fun periodLabel(period: String) = when (period) { "day" -> "今日"; "month" -> "本月"; else -> "本周" }

private fun assistantDuration(millis: Long): String {
    return durationDisplayLabel(millis)
}

/** Invalid or absent dates remain null: the editor must ask the user rather than defaulting. */
fun parseAssistantDraft(input: String, today: String): AssistantScheduleDraft {
    val text = input.replace(Regex("\\s+"), "").replace(Regex("^(记一下|记下|记住|记录|提醒我|帮我记)"), "")
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
    val base = Calendar.getInstance().apply { time = checkNotNull(dateFormat.parse(today)) }
    val monthDay = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]").find(text)
    val iso = Regex("\\d{4}-\\d{2}-\\d{2}").find(text)
    val relative = Regex("下周[一二三四五六日天]|后天|明天|今天|今日").find(text)
    var date: String? = null
    var dateToken = ""
    when {
        monthDay != null -> {
            dateToken = monthDay.value
            val year = monthDay.groupValues[1].toIntOrNull()
            val month = monthDay.groupValues[2].toInt()
            val day = monthDay.groupValues[3].toInt()
            val years = if (year != null) year..year else base.get(Calendar.YEAR)..base.get(Calendar.YEAR) + 8
            date = years.firstNotNullOfOrNull { candidateYear ->
                val candidate = "%04d-%02d-%02d".format(Locale.ROOT, candidateYear, month, day)
                runCatching { dateFormat.parse(candidate); candidate }.getOrNull()?.takeIf { year != null || it >= today }
            }
        }
        iso != null -> {
            dateToken = iso.value
            date = runCatching { dateFormat.parse(dateToken); dateToken }.getOrNull()
        }
        relative != null -> {
            dateToken = relative.value
            val offset = when (dateToken) {
                "后天" -> 2
                "明天" -> 1
                "今天", "今日" -> 0
                else -> {
                    val currentWeekday = (base.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
                    val wantedWeekday = "一二三四五六日".indexOf(dateToken.last().let { if (it == '天') '日' else it }) + 1
                    7 - currentWeekday + wantedWeekday
                }
            }
            base.add(Calendar.DAY_OF_MONTH, offset)
            date = dateFormat.format(base.time)
        }
    }
    val timeMatch = Regex("(上午|下午|晚上)?(\\d{1,2})(?:点(?:(\\d{1,2})分?)?|:(\\d{2}))").find(text)
    var hour = timeMatch?.groupValues?.get(2)?.toIntOrNull()
    val meridian = timeMatch?.groupValues?.get(1).orEmpty()
    val validMeridian = meridian.isEmpty() || hour in 1..12
    val minute = timeMatch?.groupValues?.get(3)?.toIntOrNull() ?: timeMatch?.groupValues?.get(4)?.toIntOrNull() ?: 0
    if (hour != null && timeMatch?.groupValues?.get(1) in listOf("下午", "晚上") && hour < 12) hour += 12
    if (hour == 12 && meridian == "上午") hour = 0
    val timeValid = hour != null && hour in 0..23 && minute in 0..59 && validMeridian
    val title = text.replace(dateToken.takeIf { it.isNotEmpty() } ?: "\u0000", "").let { value -> if (timeValid) value.replace(checkNotNull(timeMatch).value, "") else value }.trim('是', '，', ',', '。').ifBlank { "" }
    val dateCandidates = Regex("(?:(\\d{4})年)?\\d{1,2}月\\d{1,2}[日号]|\\d{4}-\\d{2}-\\d{2}|下周[一二三四五六日天]|后天|明天|今天|今日").findAll(text).count()
    if (dateCandidates > 1) date = null
    val endDate = date?.let { from ->
        if (timeValid && hour == 23) dateFormat.format(Calendar.getInstance().apply {
            time = checkNotNull(dateFormat.parse(from)); add(Calendar.DAY_OF_MONTH, 1)
        }.time) else from
    }
    return AssistantScheduleDraft(title, date, allDay = timeMatch == null,
        startTime = if (timeValid) "%02d:%02d".format(Locale.ROOT, hour, minute) else null,
        endTime = if (timeValid) "%02d:%02d".format(Locale.ROOT, (checkNotNull(hour) + 1) % 24, minute) else null,
        repeatType = if ("生日" in title || "每年" in text) "yearly" else null,
        endDate = endDate, needsTimeConfirmation = timeMatch != null && !timeValid)
}
