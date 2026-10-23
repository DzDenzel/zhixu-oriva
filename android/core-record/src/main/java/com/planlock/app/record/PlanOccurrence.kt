package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Calendar-shaped input shared by plans, schedules and reminder expansion. */
data class OccurrenceDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val isoDayOfWeek: Int,
) {
    init { require(month in 1..12 && day in 1..31 && isoDayOfWeek in 1..7) }

    companion object {
        fun from(calendar: Calendar): OccurrenceDate = OccurrenceDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            PlanOccurrence.isoDayOfWeek(calendar.get(Calendar.DAY_OF_WEEK)),
        )

        fun parseIso(value: String): OccurrenceDate? = runCatching {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = format.parse(value) ?: return null
            from(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = parsed })
        }.getOrNull()
    }

    fun previous(): OccurrenceDate {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
            add(Calendar.DAY_OF_MONTH, -1)
        }
        return from(calendar)
    }

    fun plusDays(offset: Int): OccurrenceDate {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day)
            add(Calendar.DAY_OF_MONTH, offset)
        }
        return from(calendar)
    }
}

private sealed interface RepeatSelector {
    fun matches(date: OccurrenceDate): Boolean
    data object Daily : RepeatSelector { override fun matches(date: OccurrenceDate) = true }
    data class Weekly(val weekdays: Set<Int>) : RepeatSelector { override fun matches(date: OccurrenceDate) = date.isoDayOfWeek in weekdays }
    data class Monthly(val day: Int) : RepeatSelector { override fun matches(date: OccurrenceDate) = date.day == day }
    data class Yearly(val month: Int, val day: Int) : RepeatSelector { override fun matches(date: OccurrenceDate) = date.month == month && date.day == day }
}

/** The single repeat-rule decision used by both plans and schedules. */
object PlanOccurrence {
    private val typePattern = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
    private val weekdaysPattern = Regex("\"weekdays\"\\s*:\\s*\\[([^]]*)]")
    private val dayPattern = Regex("\"day\"\\s*:\\s*(\\d+)")
    private val monthPattern = Regex("\"month\"\\s*:\\s*(\\d+)")

    fun isActive(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): Boolean =
        plan.status == 1 && isWithinOccurrence(plan, nowMinutes, date) &&
            !isEnded(plan, nowMinutes, date)

    /** One end marker belongs to the occurrence's start date, including its overnight tail. */
    fun isEndedOn(plan: PlanSnapshotEntity, occurrenceDate: String): Boolean =
        plan.endedOccurrenceDate == occurrenceDate

    fun isEnded(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): Boolean =
        isEndedOn(plan, occurrenceDate(plan, nowMinutes, date))

    fun canToggleExecution(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): Boolean =
        plan.status != 1 || !isEnded(plan, nowMinutes, date)

    fun occurrenceDate(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): String {
        val start = minutesOfDay(plan.startTime) ?: 0
        val end = minutesOfDay(plan.endTime) ?: 0
        val value = if (end < start && nowMinutes < end) date.previous() else date
        return "%04d-%02d-%02d".format(Locale.ROOT, value.year, value.month, value.day)
    }

    fun isWithinOccurrence(plan: PlanSnapshotEntity, nowMinutes: Int, date: OccurrenceDate): Boolean {
        if (nowMinutes !in 0 until 1440) return false
        val start = minutesOfDay(plan.startTime) ?: return false
        val end = minutesOfDay(plan.endTime) ?: return false
        if (end == start) return false
        val inWindow = if (end > start) nowMinutes in start until end else nowMinutes >= start || nowMinutes < end
        if (!inWindow) return false
        val startDate = if (end < start && nowMinutes < end) date.previous() else date
        return occursOn(plan.repeatRule, startDate)
    }

    fun occursOn(plan: PlanSnapshotEntity, date: OccurrenceDate): Boolean = occursOn(plan.repeatRule, date)

    /** Null/blank represents the legacy daily plan rule; malformed or unknown input never expands. */
    fun occursOn(repeatRule: String?, date: OccurrenceDate): Boolean {
        if (repeatRule.isNullOrBlank()) return true
        return activeDays(repeatRule)?.matches(date) == true
    }

    fun isSupportedRule(repeatRule: String?): Boolean = repeatRule.isNullOrBlank() || activeDays(repeatRule) != null

    fun isoDayOfWeek(calendarDay: Int): Int = ((calendarDay + 5) % 7) + 1

    private fun minutesOfDay(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    /** Validation and occurrence matching deliberately share this exact parser. */
    private fun activeDays(raw: String): RepeatSelector? {
        val rule = raw.trim()
        if (!rule.startsWith('{') || !rule.endsWith('}')) return null
        return when (typePattern.find(rule)?.groupValues?.get(1)) {
            "daily" -> RepeatSelector.Daily
            "weekly" -> {
                val source = weekdaysPattern.find(rule)?.groupValues?.get(1)?.trim() ?: return null
                val parts = source.split(',')
                val values = parts.mapNotNull { it.trim().toIntOrNull() }
                values.takeIf { it.isNotEmpty() && it.size == parts.size && it.all { day -> day in 1..7 } }
                    ?.toSet()?.let(RepeatSelector::Weekly)
            }
            "monthly" -> dayPattern.find(rule)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..31 }?.let(RepeatSelector::Monthly)
            "yearly" -> {
                val month = monthPattern.find(rule)?.groupValues?.get(1)?.toIntOrNull()
                val day = dayPattern.find(rule)?.groupValues?.get(1)?.toIntOrNull()
                if (month != null && month in 1..12 && day != null && day in 1..31) RepeatSelector.Yearly(month, day) else null
            }
            else -> null
        }
    }
}
