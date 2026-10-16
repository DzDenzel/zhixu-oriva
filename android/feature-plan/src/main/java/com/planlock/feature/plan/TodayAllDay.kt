package com.planlock.feature.plan

import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.util.Locale

/*
 * BUG-102（任务包 §3）：全天日程是「今天的背景」，不是需要专心执行的事项。
 * 今日页的所有展示判断都经过这里：全天日程永不作为「正在进行」，不画 24 小时弧段，卡片不写「进行中」。
 * BUG-104：今日页统一使用完整读模型，简报、日晷、卡片和周计数不得消费折叠列表。
 */

internal fun todayTimeline(
    date: String,
    plans: List<PlanSnapshotEntity>,
    schedules: List<ScheduleSnapshotEntity>,
    nowMinutes: Int,
): List<TimelineEntry> = DayTimeline.build(date, plans, schedules, nowMinutes, collapseOverlaps = false)

/** 全天日程：读模型以 sourceLabel「全天日程」标记，范围恒为 0..1440。 */
internal val TimelineEntry.isAllDay: Boolean
    get() = kind == TimelineKind.SCHEDULE && sourceLabel == "全天日程"

/** 今日页意义上的「正在进行」：只有定时安排才算。 */
internal val TimelineEntry.isRunningNow: Boolean
    get() = state == TimelineState.RUNNING && !isAllDay

/** 日晷的两层：定时安排画弧段；全天日程只在内圈画一道细环（多条只画一圈，取第一条的颜色）。 */
internal data class TodayDialLayers(val arcs: List<TimelineEntry>, val allDayRing: TimelineEntry?)

internal fun todayDialLayers(entries: List<TimelineEntry>): TodayDialLayers =
    TodayDialLayers(entries.filterNot { it.isAllDay }, entries.firstOrNull { it.isAllDay })

/**
 * 简报一句话（任务包 §3.1 优先级）：
 * 正在进行的定时安排 → 下一项定时安排 → 全天日程「今天：『X』（等 N 项）。」→ 定时安排都已结束 → 空态（null）。
 * 与定时安排并存时，句子只讲定时安排。
 */
internal fun todayBriefLine(entries: List<TimelineEntry>, isToday: Boolean): String? {
    if (!isToday) return null
    val timed = entries.filterNot { it.isAllDay }
    val allDay = entries.filter { it.isAllDay }
    val running = timed.firstOrNull { it.state == TimelineState.RUNNING }
    val upcoming = timed.filter { it.state == TimelineState.UPCOMING }
    val next = upcoming.firstOrNull()
    return when {
        running != null -> "正在进行「${running.title}」，专心就好。"
        next != null -> "下一项 %02d:%02d「%s」%s".format(Locale.US, (next.startMin / 60) % 24, next.startMin % 60, next.title,
            if (upcoming.size > 1) "，之后还有 ${upcoming.size - 1} 项。" else "。")
        allDay.size == 1 -> "今天：「${allDay.first().title}」。"
        allDay.size > 1 -> "今天：「${allDay.first().title}」等 ${allDay.size} 项。"
        timed.isNotEmpty() -> "今天的安排都结束了。"
        else -> null
    }
}

/** 时间卡状态行：全天日程只写「日程」，不写「进行中」（任务包 K3）。 */
internal fun todayRowStatus(entry: TimelineEntry): String = when {
    entry.isAllDay -> "日程"
    else -> (if (entry.kind == TimelineKind.PLAN) "计划" else "日程") + " · " + when (entry.state) {
        TimelineState.RUNNING -> "进行中"
        TimelineState.UPCOMING -> "待开始"
        TimelineState.ENDED -> "已结束"
        TimelineState.PAUSED -> "已暂停"
    }
}
