package com.planlock.app

import com.planlock.app.api.*
import com.planlock.app.record.*
import com.planlock.app.sync.AppSync
import com.planlock.feature.plan.PlanTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One accepted projection shared by the assistant and JS queries; never requests refresh. */
suspend fun loadAssistantSnapshot(store: RecordStore, sync: AppSync, requestedDate: String = ""): AssistantSnapshot {
    val userId = TokenStore.userId
    val plans = store.plans()
    val now = Calendar.getInstance()
    val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    val currentDate = format.format(now.time)
    val date = requestedDate.ifEmpty { currentDate }
    val occurrence = Calendar.getInstance().apply { time = requireNotNull(format.parse(date)) }
    val cached = sync.cache.read(userId)
    val reports = listOf("day", "week", "month").mapNotNull { period -> cached.stats(period)?.let { period to it } }.toMap()
    val schedules = store.schedules()
    check(userId == TokenStore.userId) { "User changed during query" }
    return AssistantSnapshot(
        today = date,
        arrangements = DayTimeline.build(date, plans, schedules, minutes, collapseOverlaps = false)
            .map { "${DayTimeline.rangeLabel(it)} · ${it.title}（${it.sourceLabel}）" },
        plans = plans.map { AssistantPlanState(it.id, it.name, it.status == 1,
            date == currentDate && PlanOccurrence.isWithinOccurrence(it, minutes, OccurrenceDate.from(occurrence)),
            if (date == currentDate) PlanTime.remainingMinutes(it, minutes) else 0) },
        continuousDays = cached.stats("week")?.continuousDays, reports = reports,
        todayUnlocks = cached.today?.unlockCount.takeIf { date == currentDate },
    )
}
