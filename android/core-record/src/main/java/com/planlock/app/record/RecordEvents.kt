package com.planlock.app.record

import com.planlock.app.api.dto.ExecutionRecordRequest
import com.planlock.app.api.dto.UnlockRecordRequest
import com.planlock.app.api.dto.ViolationRecordRequest
import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class RecordEventFactory(
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val randomClientId: () -> String = { UUID.randomUUID().toString() },
) {
    fun executionStarted(plan: PlanSnapshotEntity, atMillis: Long): ExecutionRecordRequest = ExecutionRecordRequest(
        planId = plan.id,
        planDate = occurrenceDate(plan, atMillis),
        status = STATUS_STARTED,
        startedAt = dateTime(atMillis),
        clientId = executionClientId(plan, atMillis, "start"),
    )

    fun executionEnded(plan: PlanSnapshotEntity, atMillis: Long, completed: Boolean): ExecutionRecordRequest = ExecutionRecordRequest(
        planId = plan.id,
        planDate = occurrenceDate(plan, atMillis),
        status = if (completed) STATUS_COMPLETED else STATUS_UNLOCKED,
        endedAt = dateTime(atMillis),
        clientId = executionClientId(plan, atMillis, "end"),
    )

    fun violation(planId: Long, packageName: String, appName: String?, atMillis: Long): ViolationRecordRequest = ViolationRecordRequest(
        planId = planId,
        recordAt = dateTime(atMillis),
        appName = appName,
        packageName = packageName,
        clientId = randomClientId(),
    )

    fun behavior(interval: ForegroundUsageInterval): ViolationRecordRequest = ViolationRecordRequest(
        planId = interval.planId,
        recordAt = dateTime(interval.endedAtMillis),
        appName = interval.appName,
        packageName = interval.packageName,
        startedAt = dateTime(interval.startedAtMillis),
        endedAt = dateTime(interval.endedAtMillis),
        durationMs = interval.durationMillis,
        sourceLevel = interval.source.name,
        clientId = randomClientId(),
    )

    fun unlock(planId: Long, atMillis: Long, method: String = "backup_password"): UnlockRecordRequest = UnlockRecordRequest(
        planId = planId,
        unlockAt = dateTime(atMillis),
        method = method,
        clientId = randomClientId(),
    )

    private fun executionClientId(plan: PlanSnapshotEntity, atMillis: Long, edge: String): String {
        val start = plan.startTime.orEmpty().filter(Char::isDigit).take(6).padEnd(6, '0')
        return "exec-${plan.id}-${occurrenceDate(plan, atMillis)}-$start-$edge"
    }

    private fun occurrenceDate(plan: PlanSnapshotEntity, millis: Long): String {
        val start = minutesOfDay(plan.startTime) ?: return date(millis)
        val end = minutesOfDay(plan.endTime) ?: return date(millis)
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        val current = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (end < start && current < end) calendar.add(Calendar.DAY_OF_MONTH, -1)
        return date(calendar.timeInMillis)
    }

    private fun minutesOfDay(value: String?): Int? {
        val parts = value?.split(':') ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    private fun date(millis: Long): String = format("yyyy-MM-dd", millis)
    private fun dateTime(millis: Long): String = format("yyyy-MM-dd'T'HH:mm:ss", millis)

    private fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = this@RecordEventFactory.timeZone }.format(Date(millis))

    companion object {
        const val STATUS_STARTED = 0
        const val STATUS_COMPLETED = 1
        const val STATUS_UNLOCKED = 2
    }
}

sealed interface PendingUpload {
    data class Execution(val request: ExecutionRecordRequest) : PendingUpload
    data class Violation(val request: ViolationRecordRequest) : PendingUpload
    data class Unlock(val request: UnlockRecordRequest) : PendingUpload
}

fun PendingRecordEntity.toPendingUpload(): PendingUpload? = when (type) {
    "execution" -> planId?.let { PendingUpload.Execution(ExecutionRecordRequest(it, planDate.orEmpty(), status ?: 0, startedAt, endedAt, clientId)) }
    "violation" -> planId?.let {
        PendingUpload.Violation(
            ViolationRecordRequest(
                planId = it,
                recordAt = recordAt.orEmpty(),
                appName = appName,
                packageName = packageName,
                startedAt = startedAt,
                endedAt = endedAt,
                durationMs = durationMs,
                sourceLevel = sourceLevel,
                clientId = clientId,
            )
        )
    }
    "unlock" -> planId?.let { PendingUpload.Unlock(UnlockRecordRequest(it, unlockAt.orEmpty(), method ?: "backup_password", clientId)) }
    else -> null
}

data class ExecutionEdges(
    val endedPlanId: Long? = null,
    val startedPlanId: Long? = null,
)

object ExecutionEdgeDetector {
    fun detect(previousPlanId: Long?, currentPlanId: Long?): ExecutionEdges = when {
        previousPlanId == currentPlanId -> ExecutionEdges()
        else -> ExecutionEdges(endedPlanId = previousPlanId, startedPlanId = currentPlanId)
    }
}
