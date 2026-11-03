package com.planlock.feature.settings

import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import com.planlock.app.api.ReminderReadiness
import com.planlock.app.api.reminderDelayLabel

/** REQ-044 U-R5 / U-R6：把 ReminderReadiness 变成页面要显示的行与结论；纯函数，不读系统。 */
internal data class ReminderGuideRow(val key: ReadinessKey, val title: String, val hint: String, val state: ReadinessState)

internal enum class ReminderGuideVerdict { READY, SELF_CHECK, NEEDS_ACTION }

internal data class ReminderGuideModel(
    /** 最近一次被推迟时的页首一句，否则 null。 */
    val deferralHeader: String?,
    val deferralPlan: String?,
    val verdict: ReminderGuideVerdict,
    val missing: Int,
    val rows: List<ReminderGuideRow>,
    val calendarOn: Boolean,
    /** 日历开关组放在清单之前：有过推迟，或是 ColorOS（真机实测：应用闹钟被推迟时系统日历仍准点，§5.89）。 */
    val calendarFirst: Boolean,
    /** 关着时标「推荐」并写明原因；开着就不再劝。 */
    val calendarRecommendation: String?,
)

/** 始终列出的四项：通知 + 除通知外最多 3 项（REQ-044 §4 第 4 条）。 */
private val essential = listOf(ReadinessKey.NOTIFICATIONS, ReadinessKey.FULL_SCREEN, ReadinessKey.BACKGROUND, ReadinessKey.VENDOR_NOTIFICATION)

/** 通常安装即开启的三项：只在未开启时出现（「有内容才出现」）。 */
private val usuallyOn = listOf(ReadinessKey.EXACT_ALARM, ReadinessKey.PROMOTED, ReadinessKey.PUNCTUAL_START)

internal fun readinessTitle(key: ReadinessKey): String = when (key) {
    ReadinessKey.NOTIFICATIONS -> "通知"
    ReadinessKey.FULL_SCREEN -> "全屏提醒"
    ReadinessKey.BACKGROUND -> "后台运行"
    ReadinessKey.VENDOR_NOTIFICATION -> "横幅、锁屏与铃声"
    ReadinessKey.EXACT_ALARM -> "准点闹钟"
    ReadinessKey.PROMOTED -> "实时通知（胶囊）"
    ReadinessKey.PUNCTUAL_START -> "准时开始"
    ReadinessKey.CALENDAR_FALLBACK -> "用系统日历再提醒一次"
}

internal fun readinessHint(key: ReadinessKey): String = when (key) {
    ReadinessKey.NOTIFICATIONS -> "提醒和计划开始都靠通知送达"
    ReadinessKey.FULL_SCREEN -> "计划开始时在锁屏上直接亮屏显示"
    ReadinessKey.BACKGROUND -> "ColorOS：在应用耗电管理里把知序设为「完全允许后台行为」"
    ReadinessKey.VENDOR_NOTIFICATION -> "ColorOS：在通知管理 › 知序里打开横幅、锁屏显示和铃声"
    ReadinessKey.EXACT_ALARM -> "让提醒按分钟准点送达"
    ReadinessKey.PROMOTED -> "把倒计时放进状态栏胶囊"
    ReadinessKey.PUNCTUAL_START -> "用系统闹钟通道让计划准点开始"
    ReadinessKey.CALENDAR_FALLBACK -> "手机推迟提醒时，由系统日历按时提醒一次（部分日历应用不支持）"
}

/** 状态签文字；口径与权限页一致（U-R6）。 */
internal fun readinessStateLabel(state: ReadinessState): String = when (state) {
    ReadinessState.ON -> "已开启"
    ReadinessState.OFF -> "未开启"
    ReadinessState.UNKNOWN -> "无法读取"
}

/** OPPO / 一加 / realme 的系统为 ColorOS（及其海外版），会代理后台应用的闹钟（BUG-132）。 */
internal fun isColorOsManufacturer(manufacturer: String): Boolean =
    manufacturer.trim().lowercase() in setOf("oppo", "oneplus", "realme")

internal const val COLOR_OS_CALENDAR_REASON = "推荐：这台手机会推迟应用的提醒，系统日历能按时提醒"

internal fun reminderGuideModel(readiness: ReminderReadiness, colorOs: Boolean = false): ReminderGuideModel {
    val byKey = readiness.items.associateBy { it.key }
    fun state(key: ReadinessKey) = byKey[key]?.state ?: ReadinessState.UNKNOWN
    val deferral = readiness.lastDeferral
    val order = if (deferral != null) listOf(ReadinessKey.BACKGROUND) + (essential - ReadinessKey.BACKGROUND) else essential
    val rows = (order + usuallyOn.filter { state(it) == ReadinessState.OFF })
        .map { ReminderGuideRow(it, readinessTitle(it), readinessHint(it), state(it)) }
    val missing = rows.count { it.state == ReadinessState.OFF }
    val verdict = when {
        missing > 0 -> ReminderGuideVerdict.NEEDS_ACTION
        rows.any { it.state == ReadinessState.UNKNOWN } -> ReminderGuideVerdict.SELF_CHECK
        else -> ReminderGuideVerdict.READY
    }
    return ReminderGuideModel(
        deferralHeader = deferral?.let { "上次提醒被系统推迟了 ${reminderDelayLabel(it.lateMs)}" },
        deferralPlan = deferral?.planName,
        verdict = verdict,
        missing = missing,
        rows = rows,
        calendarOn = state(ReadinessKey.CALENDAR_FALLBACK) == ReadinessState.ON,
        calendarFirst = colorOs || deferral != null,
        calendarRecommendation = COLOR_OS_CALENDAR_REASON.takeIf { colorOs && state(ReadinessKey.CALENDAR_FALLBACK) != ReadinessState.ON },
    )
}

internal fun verdictTitle(model: ReminderGuideModel): String = when (model.verdict) {
    ReminderGuideVerdict.READY -> "提醒已就绪"
    ReminderGuideVerdict.SELF_CHECK -> "能检查的都已开启"
    ReminderGuideVerdict.NEEDS_ACTION -> "还差 ${model.missing} 项"
}

internal fun verdictDetail(model: ReminderGuideModel): String = when (model.verdict) {
    ReminderGuideVerdict.READY -> "计划会准时响起"
    ReminderGuideVerdict.SELF_CHECK -> "「无法读取」的项系统不让应用查看，按提示确认一次即可"
    ReminderGuideVerdict.NEEDS_ACTION -> "点「去开启」，设置完返回这里会自动刷新"
}
