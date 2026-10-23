package com.planlock.feature.plan

import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.entity.PlanSnapshotEntity
import java.util.Calendar
import java.util.Locale

enum class EndPlanChoice { TODAY, ENTIRE_PLAN }

internal fun needsEndChoice(plan: PlanSnapshotEntity, action: ProtectedPlanAction, minutes: Int, date: OccurrenceDate): Boolean =
    action == ProtectedPlanAction.PAUSE && PlanOccurrence.isActive(plan, minutes, date)

/** Expand the shared repeat rule, retaining the user's timezone (including DST). */
internal fun nextRepeatDescription(plan: PlanSnapshotEntity, now: Calendar): String {
    val next = nextRepeatOccurrence(plan, now) ?: return "下一次"
    val today = OccurrenceDate.from(now)
    val nextDay = OccurrenceDate.from(next)
    val offset = (0..7).firstOrNull { today.plusDays(it) == nextDay }
    val day = when {
        offset == 0 -> "今天"
        offset == 1 -> "明天"
        offset != null && plan.repeatRule?.contains("weekly") == true -> "周" + "一二三四五六日"[nextDay.isoDayOfWeek - 1]
        else -> "${next.get(Calendar.MONTH) + 1}月${next.get(Calendar.DAY_OF_MONTH)}日"
    }
    return "$day ${String.format(Locale.ROOT, "%02d:%02d", next.get(Calendar.HOUR_OF_DAY), next.get(Calendar.MINUTE))}"
}

/** Choice precedes all password access; dismissing a prompt never schedules a write. */
internal suspend fun dispatchPlanAction(
    pending: PendingPlanAction,
    choosingEnd: Boolean,
    requirement: suspend () -> PlanActionRequirement,
    perform: suspend () -> PlanActionResult,
    onPrompt: (PendingPlanAction) -> Unit,
) {
    if (choosingEnd) {
        onPrompt(pending.copy(choosingEnd = true))
        return
    }
    when (requirement()) {
        PlanActionRequirement.FREE -> when (val result = perform()) {
            is PlanActionResult.Completed -> pending.onCompleted(result.warning)
            is PlanActionResult.Failed -> onPrompt(pending.copy(choosingEnd = false, failure = result.message))
            else -> onPrompt(pending.copy(choosingEnd = false, failure = "计划保护状态已变化，请重新确认。"))
        }
        PlanActionRequirement.PASSWORD -> onPrompt(pending.copy(choosingEnd = false, setupRequired = false))
        PlanActionRequirement.SETUP -> onPrompt(pending.copy(choosingEnd = false, setupRequired = true))
    }
}
