package com.planlock.feature.stats

import com.planlock.app.api.dto.DayCell
import com.planlock.app.api.dto.Ratio
import com.planlock.app.record.OccurrenceDate
import java.util.Locale

/*
 * 统计页「我的节奏」的纯展示投影（v4.3，U3 方向 C，负责人 2026-10-19 定稿）。
 * 数据只来自 RhythmSummary；这里把它变成「周 / 月」柱状图的区间、柱段与几句人话，便于单测。
 */

enum class RhythmRange { WEEK, MONTH }

/** 一根柱子。[cell] 为 null：未来的天（[future]）或早于统计窗口的天。 */
internal data class RhythmBar(val date: OccurrenceDate, val cell: DayCell?, val future: Boolean, val isToday: Boolean) {
    val done: Int get() = cell?.completed ?: 0
    val early: Int get() = cell?.earlyEnded ?: 0
    val missed: Int get() = cell?.missed ?: 0
    /** 今天还没到时间的次数（planned 里尚未落到三种结果的部分）。 */
    val upcoming: Int get() = cell?.takeIf { it.isToday }?.let { (it.planned - it.completed - it.earlyEnded - it.missed).coerceAtLeast(0) } ?: 0
    val height: Int get() = done + early + missed + upcoming
}

internal data class RhythmPeriod(
    val range: RhythmRange,
    val offset: Int,
    val bars: List<RhythmBar>,
    /** 这段时间里能往前翻吗（还有更早的数据）。 */
    val canGoBack: Boolean,
) {
    val canGoForward: Boolean get() = offset < 0
    val maxHeight: Int get() = bars.maxOfOrNull { it.height }?.coerceAtLeast(1) ?: 1
    private val known: List<RhythmBar> get() = bars.filter { it.cell != null && !it.future }
    val completed: Int get() = known.sumOf { it.done }
    val counted: Int get() = known.sumOf { it.done + it.early + it.missed }
    val upcoming: Int get() = known.sumOf { it.upcoming }
    /** 日均完成（只算已有数据的天）。 */
    val averageCompleted: Double get() = if (known.isEmpty()) 0.0 else completed.toDouble() / known.size
    val knownDays: Int get() = known.size
}

internal fun OccurrenceDate.iso(): String = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)

/** 周 = 周一至周日；月 = 1 日至月末。[offset] 0 = 本周 / 本月，-1 = 上一周 / 上个月。 */
internal fun rhythmPeriod(range: RhythmRange, offset: Int, days: List<DayCell>, today: OccurrenceDate): RhythmPeriod {
    val byDate = days.associateBy { it.date }
    val start = when (range) {
        RhythmRange.WEEK -> today.plusDays(-(today.isoDayOfWeek - 1) + offset * 7)
        RhythmRange.MONTH -> monthStart(today, offset.coerceAtMost(0))
    }
    val length = when (range) {
        RhythmRange.WEEK -> 7
        RhythmRange.MONTH -> daysInMonth(start.year, start.month)
    }
    val todayIso = today.iso()
    val bars = (0 until length).map { i ->
        val date = start.plusDays(i)
        val iso = date.iso()
        RhythmBar(date, byDate[iso], future = iso > todayIso, isToday = iso == todayIso)
    }
    val earliest = days.minOfOrNull { it.date }
    return RhythmPeriod(range, offset, bars, canGoBack = earliest != null && earliest < start.iso())
}

private fun monthStart(today: OccurrenceDate, offset: Int): OccurrenceDate {
    var year = today.year
    var month = today.month + offset
    while (month < 1) { month += 12; year -= 1 }
    while (month > 12) { month -= 12; year += 1 }
    // 从今天倒推到目标月 1 日（OccurrenceDate 只能按天移动）
    var date = today.plusDays(-(today.day - 1))
    while (date.year != year || date.month != month) date = date.plusDays(-1).let { it.plusDays(-(it.day - 1)) }
    return date
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** 区间标题：「本周」「10 月 6 日 – 12 日」「9 月 29 日 – 10 月 5 日」「本月」「9 月」。 */
internal fun rhythmRangeLabel(period: RhythmPeriod, today: OccurrenceDate): String {
    val first = period.bars.first().date
    val last = period.bars.last().date
    return when (period.range) {
        RhythmRange.WEEK -> if (period.offset == 0) "本周" else
            "${first.month} 月 ${first.day} 日 – " + (if (last.month != first.month) "${last.month} 月 " else "") + "${last.day} 日"
        RhythmRange.MONTH -> if (period.offset == 0) "本月" else if (first.year != today.year) "${first.year} 年 ${first.month} 月" else "${first.month} 月"
    }
}

/** 卡片标题下的一句：「本周完成 1 / 1 次，还有 2 次没到时间」「9 月完成 40 / 52 次；日均 1.3 次」。 */
internal fun rhythmPeriodSummary(period: RhythmPeriod, label: String): String {
    if (period.knownDays == 0) return "这段时间没有记录"
    val name = when (label) { "本周", "本月" -> label; else -> if (period.range == RhythmRange.WEEK) "这周" else label }
    // v4.4：一次都没安排时不写「完成 0 / 0 次；日均 0.0 次」
    if (period.counted + period.upcoming == 0) return "${name}没有安排计划"
    return buildString {
        append("${name}完成 ${period.completed} / ${period.counted + period.upcoming} 次")
        if (period.upcoming > 0) append("，还有 ${period.upcoming} 次没到时间")
        if (period.knownDays > 1) append("；日均 ${"%.1f".format(Locale.ROOT, period.averageCompleted)} 次")
    }
}

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun weekdayLabel(date: OccurrenceDate): String = "周" + weekdays[date.isoDayOfWeek - 1]

/** 点一天后的明细：「10 月 15 日 周四：完成 2 / 3，提前结束 1；专注 1 小时 20 分」。 */
internal fun rhythmDayLine(bar: RhythmBar): String {
    val head = "${bar.date.month} 月 ${bar.date.day} 日 ${weekdayLabel(bar.date)}" + if (bar.isToday) "（今天）" else ""
    val cell = bar.cell ?: return "$head：" + if (bar.future) "还没到" else "没有记录"
    if (cell.planned == 0 && cell.completed + cell.earlyEnded + cell.missed == 0) return "$head：没有安排计划"
    return buildString {
        append("$head：完成 ${cell.completed} / ${cell.planned}")
        if (cell.earlyEnded > 0) append("，提前结束 ${cell.earlyEnded}")
        if (cell.missed > 0) append("，未执行 ${cell.missed}")
        if (bar.upcoming > 0) append("，还有 ${bar.upcoming} 次没到时间")
        append("；专注 ${minutesLabel(cell.focusMinutes)}")
    }
}

/** 窄格里的短写（v4.6）：「45分钟」「1小时20分」「28.5小时」——不加空格，免得大字号手机上折行。 */
internal fun compactMinutes(minutes: Int): String = minutesLabel(minutes).replace(" ", "")

/** 「45 分钟」「1 小时 20 分」「28.5 小时」（十小时以上取一位小数）。 */
internal fun minutesLabel(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m < 60 -> "$m 分钟"
        m < 600 -> if (m % 60 == 0) "${m / 60} 小时" else "${m / 60} 小时 ${m % 60} 分"
        else -> "${"%.1f".format(Locale.ROOT, m / 60.0).removeSuffix(".0")} 小时"
    }
}

/** 与上一周期比较。[lowerIsBetter]：提前结束少才是好事。返回文字与是否「变好」（null = 持平或无可比）。 */
internal data class Delta(val text: String, val better: Boolean?, val short: String = text)

internal fun focusDelta(now: Int, before: Int): Delta {
    val diff = now - before
    return when {
        before == 0 && now == 0 -> Delta("暂无对比", null)
        diff == 0 -> Delta("和前 30 天持平", null, "持平")
        diff > 0 -> Delta("比前 30 天多 ${minutesLabel(diff)}", true, "+${compactMinutes(diff)}")
        else -> Delta("比前 30 天少 ${minutesLabel(-diff)}", false, "−${compactMinutes(-diff)}")
    }
}

internal fun earlyDelta(now: Int, before: Int): Delta = when {
    now == before -> if (now == 0) Delta("没有提前结束", null) else Delta("和前 30 天持平", null, "持平")
    now < before -> Delta("比前 30 天少 ${before - now} 次", true, "−${before - now}次")
    else -> Delta("比前 30 天多 ${now - before} 次", false, "+${now - before}次")
}

/** 完成率环下的一行：「比上周高 17 个百分点」；没有可计入次数时说明清楚，不显示 0%。 */
internal fun rateDelta(now: Ratio, before: Ratio): Delta = when {
    now.total == 0 -> Delta("近 7 天还没有可计入的计划", null)
    before.total == 0 -> Delta("上周没有可对比的记录", null)
    now.percent == before.percent -> Delta("和上周持平", null)
    now.percent > before.percent -> Delta("比上周高 ${now.percent - before.percent} 个百分点", true)
    else -> Delta("比上周低 ${before.percent - now.percent} 个百分点", false)
}

/** 完成率环旁的主句：「近 7 天完成 5 / 10 次」；没有可计入次数时说明清楚。 */
internal fun rhythmHeroLine(rate: Ratio): String =
    if (rate.total == 0) "近 7 天还没有可计入的计划" else "近 7 天完成 ${rate.completed} / ${rate.total} 次"
