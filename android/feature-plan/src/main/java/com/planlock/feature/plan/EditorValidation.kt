package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.design.FormIssue
import com.planlock.design.RepeatPreset

/*
 * BUG-112：保存前的校验，按页面从上到下的顺序给出未通过项。只做判断，不改保存逻辑；
 * 字段键与页面里 inkAttention / 滚动定位用的键一致。
 */

internal object PlanField {
    const val NAME = "name"
    const val REPEAT = "repeat"
}

internal object ScheduleField {
    const val TITLE = "title"
    const val DATE = "date"
    const val TIME = "time"
    const val REPEAT = "repeat"
    const val REMINDER = "reminder"
}

internal fun planFormIssues(name: String, preset: RepeatPreset, weekdays: Set<Int>): List<FormIssue> = buildList {
    if (name.isBlank()) add(FormIssue(PlanField.NAME, "请填写计划名称"))
    if (preset == RepeatPreset.CUSTOM && weekdays.isEmpty()) add(FormIssue(PlanField.REPEAT, "重复：请至少选择一天"))
}

private val CLOCK = Regex("([01]\\d|2[0-3]):[0-5]\\d")

internal fun scheduleFormIssues(
    title: String,
    startDate: String,
    endDate: String,
    allDay: Boolean,
    draft: ScheduleEditDraft,
): List<FormIssue> = buildList {
    if (title.isBlank()) add(FormIssue(ScheduleField.TITLE, "请填写日程标题"))
    val datesValid = OccurrenceDate.parseIso(startDate) != null && OccurrenceDate.parseIso(endDate) != null
    when {
        !datesValid -> add(FormIssue(ScheduleField.DATE, "请选择开始和结束日期"))
        endDate < startDate -> add(FormIssue(ScheduleField.DATE, "结束日期不能早于开始日期"))
    }
    if (!draft.timesValid(allDay)) {
        add(FormIssue(ScheduleField.TIME, "请选择开始和结束时间，或打开「全天」"))
    } else if (!allDay && datesValid && startDate == endDate &&
        CLOCK.matches(draft.startTime) && CLOCK.matches(draft.endTime) && draft.endTime < draft.startTime) {
        add(FormIssue(ScheduleField.TIME, "结束时间要晚于开始时间"))
    }
    if (!draft.ruleCanBeSaved) add(FormIssue(ScheduleField.REPEAT, "原有重复规则暂不支持保存，请重新选择"))
    if (!draft.reminderValid) add(FormIssue(ScheduleField.REMINDER, "提前提醒请填写不小于 0 的整数"))
}

/** 某个字段当前的行内提示（只在点过保存之后显示）。 */
internal fun List<FormIssue>.messageFor(field: String): String? = firstOrNull { it.field == field }?.message
