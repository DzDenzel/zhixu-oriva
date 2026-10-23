package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.util.Locale

/*
 * BUG-105：计划页两个切换签的数字与下方列表由这里同一份结果推导——签上写几条，列表里就能找到几条。
 * 日程：每条有效日程在列表里出现且只出现一次（不再按「未来一年的每次发生」展开）。
 *   顺序（负责人 2026-10-18）：还没结束的在前，按日期从近到远、按月分组；已过去的排在最后一段「已过去」，最近的在前。
 * 计划：数字 = 「全部」视图里的条目数（可执行的 + 已暂停 / 时间不完整的）。
 */

/** 日程列表中的一行：[date] 是这一行挂靠的日期（一次性 = 开始日期；重复 = 今天起的下一次，没有则开始日期）。 */
internal data class ScheduleShelfEntry(val schedule: ScheduleSnapshotEntity, val date: String, val past: Boolean)

internal data class ScheduleShelfGroup(val key: String, val label: String, val entries: List<ScheduleShelfEntry>)

/** [upcoming] 还没结束的（含正在进行的跨天日程），月份升序；[past] 已过去的，月份与日期都倒序。 */
internal data class ScheduleShelf(val upcoming: List<ScheduleShelfGroup>, val past: List<ScheduleShelfGroup>) {
    val groups: List<ScheduleShelfGroup> get() = upcoming + past
    val entries: List<ScheduleShelfEntry> get() = groups.flatMap { it.entries }
    /** 切换签上的数字：就是列表的条目数（「已过去」段收起时也算，展开即可找到）。 */
    val count: Int get() = groups.sumOf { it.entries.size }
    val pastCount: Int get() = past.sumOf { it.entries.size }
}

/** 有效日程：未删除（删除即移出快照表）且状态为启用；同一 ID 只算一条（待确认冲突不另算）。 */
internal fun validSchedules(schedules: List<ScheduleSnapshotEntity>): List<ScheduleSnapshotEntity> =
    schedules.filter { it.status == 1 }.distinctBy { it.id }

internal fun scheduleShelf(schedules: List<ScheduleSnapshotEntity>, today: OccurrenceDate): ScheduleShelf {
    val todayKey = isoKey(today)
    val entries = validSchedules(schedules).map { schedule ->
        val repeating = !schedule.repeatRule.isNullOrBlank()
        val next = if (repeating) nextOccurrence(schedule, today) else null
        val date = next ?: schedule.startDate
        val past = if (repeating) next == null && schedule.startDate < todayKey else schedule.endDate.ifBlank { schedule.startDate } < todayKey
        ScheduleShelfEntry(schedule, date, past)
    }
    val byTime = compareBy<ScheduleShelfEntry> { if (it.schedule.allDay) "" else it.schedule.startTime.orEmpty() }.thenBy { it.schedule.id }
    val upcoming = entries.filterNot { it.past }.sortedWith(compareBy<ScheduleShelfEntry> { it.date }.then(byTime))
    val past = entries.filter { it.past }.sortedWith(compareByDescending<ScheduleShelfEntry> { it.date }.then(byTime))
    fun group(list: List<ScheduleShelfEntry>, prefix: String) = list.groupBy { it.date.take(7) }.map { (month, group) ->
        ScheduleShelfGroup(prefix + month, monthLabel(month, today.year), group)
    }
    return ScheduleShelf(group(upcoming, ""), group(past, "past-"))
}

/** 「10 月」；不在今年时带上年份「2027 年 10 月」，避免把明年的生日看成今年的。 */
internal fun monthLabel(yearMonth: String, currentYear: Int): String {
    val year = yearMonth.take(4).toIntOrNull()
    val month = yearMonth.drop(5).take(2).toIntOrNull() ?: return yearMonth
    return if (year == null || year == currentYear) "$month 月" else "$year 年 $month 月"
}

/** 计划签上的数字 = 「全部」视图条目数（与 PlanOverview 同一口径：按 ID 去重）。 */
internal fun planShelfCount(model: PlanOverviewModel): Int = model.scheduled.size + model.stored.size

private fun nextOccurrence(schedule: ScheduleSnapshotEntity, today: OccurrenceDate): String? {
    val start = OccurrenceDate.parseIso(schedule.startDate) ?: return null
    val todayKey = isoKey(today)
    var day = if (schedule.startDate > todayKey) start else today
    repeat(367) {
        if (PlanOccurrence.occursOn(schedule.repeatRule, day)) return isoKey(day)
        day = day.plusDays(1)
    }
    return null
}

private fun isoKey(date: OccurrenceDate): String = "%04d-%02d-%02d".format(Locale.US, date.year, date.month, date.day)
