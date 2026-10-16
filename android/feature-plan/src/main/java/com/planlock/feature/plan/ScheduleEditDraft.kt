package com.planlock.feature.plan

import com.planlock.app.api.AssistantScheduleDraft
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.record.PlanOccurrence
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Keeps absent and unedited legacy values intact; picker defaults become data only on confirmation. */
internal data class ScheduleEditDraft(
    val original: ScheduleSnapshotEntity?,
    val startTime: String,
    val endTime: String,
    val reminder: String,
    val repeatType: String?,
    private val startTimeChanged: Boolean = false,
    private val endTimeChanged: Boolean = false,
    private val repeatChanged: Boolean = false,
) {
    val reminderValid: Boolean get() = reminder.isEmpty() || reminder.toIntOrNull()?.let { it >= 0 } == true
    val ruleCanBeSaved: Boolean get() = repeatChanged || original == null || PlanOccurrence.isSupportedRule(original.repeatRule)
    val showsPreservedRule: Boolean get() = original?.repeatRule != null && !repeatChanged && !PlanOccurrence.isSupportedRule(original.repeatRule)
    fun timesValid(allDay: Boolean): Boolean = allDay || (validTime(startTime) && validTime(endTime))
    private fun validTime(value: String) = CLOCK.matches(value) || (original != null && value.isEmpty())
    fun withStartTime(value: String) = copy(startTime = value, startTimeChanged = true)
    fun withEndTime(value: String) = copy(endTime = value, endTimeChanged = true)
    fun withReminder(value: String) = copy(reminder = value)
    fun withRepeatType(value: String?) = copy(repeatType = value, repeatChanged = true)
    fun request(title: String, startDate: String, endDate: String, allDay: Boolean,
                location: String, note: String, linkedPlanId: Long?, tzId: String): ScheduleUpsertRequest {
        val rule = if (original != null && !repeatChanged) original.repeatRule else when (repeatType) {
            "daily" -> """{"type":"daily"}"""
            "weekly" -> """{"type":"weekly","weekdays":[${isoWeekday(startDate)}]}"""
            "monthly" -> """{"type":"monthly","day":${startDate.takeLast(2).toIntOrNull() ?: 1}}"""
            "yearly" -> """{"type":"yearly","month":${startDate.substring(5, 7).toIntOrNull() ?: 1},"day":${startDate.takeLast(2).toIntOrNull() ?: 1}}"""
            else -> null
        }
        fun clock(value: String, stored: String?, changed: Boolean): String? = when {
            allDay -> null
            original != null && !changed -> stored
            else -> value.ifEmpty { null }
        }
        fun optional(value: String, stored: String?): String? =
            if (original != null && value == stored.orEmpty()) stored else value.ifBlank { null }
        return ScheduleUpsertRequest(title, startDate,
            clock(startTime, original?.startTime, startTimeChanged), endDate,
            clock(endTime, original?.endTime, endTimeChanged), allDay,
            optional(location, original?.location), optional(note, original?.note),
            reminder.toIntOrNull(), rule, linkedPlanId, if (original != null) original.tzId else tzId)
    }

    companion object {
        private val CLOCK = Regex("([01]\\d|2[0-3]):[0-5]\\d")
        fun fromSnapshot(current: ScheduleSnapshotEntity?, draft: AssistantScheduleDraft? = null) = ScheduleEditDraft(
            current,
            if (current != null) current.startTime?.take(5).orEmpty() else draft?.startTime ?: if (draft?.needsTimeConfirmation == true) "" else "09:00",
            if (current != null) current.endTime?.take(5).orEmpty() else draft?.endTime ?: if (draft?.needsTimeConfirmation == true) "" else "10:00",
            if (current != null) current.remindAdvanceMin?.toString().orEmpty() else "15",
            if (current != null) Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(current.repeatRule.orEmpty())?.groupValues?.get(1) else draft?.repeatType,
        )
        private fun isoWeekday(date: String): Int = runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date) ?: return 1
            val calendar = Calendar.getInstance().apply { time = parsed }
            ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        }.getOrDefault(1)
    }
}
