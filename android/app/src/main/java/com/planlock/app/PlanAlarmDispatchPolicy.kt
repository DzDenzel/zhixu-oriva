package com.planlock.app

import android.content.Intent

internal const val ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

internal enum class PlanAlarmBroadcastAction {
    ALARM_DELIVERY,
    EXACT_ALARM_PERMISSION_CHANGED,
    SYSTEM_RESCHEDULE,
    IGNORED,
}

internal fun classifyReceiverAction(action: String?): PlanAlarmBroadcastAction = when (action) {
    null -> PlanAlarmBroadcastAction.ALARM_DELIVERY
    ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> PlanAlarmBroadcastAction.EXACT_ALARM_PERMISSION_CHANGED
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    -> PlanAlarmBroadcastAction.SYSTEM_RESCHEDULE
    else -> PlanAlarmBroadcastAction.IGNORED
}

internal fun shouldRescheduleAlarms(classification: PlanAlarmBroadcastAction): Boolean =
    classification == PlanAlarmBroadcastAction.EXACT_ALARM_PERMISSION_CHANGED ||
        classification == PlanAlarmBroadcastAction.SYSTEM_RESCHEDULE

internal enum class StartForegroundOutcome { STARTED, BLOCKED, NOT_ATTEMPTED }

internal data class PlanStartAlarmDecision(
    val startNormalAlert: Boolean,
    val cancelPendingAlerts: Boolean,
    val showForegroundBlockedNotification: Boolean,
    val showDeferredNotification: Boolean,
    val recordForegroundFailure: Boolean,
)

internal fun isForegroundStartRestrictedError(error: Throwable): Boolean =
    error is IllegalStateException || error is SecurityException

internal fun decidePlanStartAlarm(
    mayStart: Boolean,
    deferred: Boolean,
    foregroundOutcome: StartForegroundOutcome,
): PlanStartAlarmDecision = when {
    !mayStart -> PlanStartAlarmDecision(
        startNormalAlert = false,
        cancelPendingAlerts = false,
        showForegroundBlockedNotification = false,
        showDeferredNotification = deferred,
        recordForegroundFailure = false,
    )
    foregroundOutcome == StartForegroundOutcome.BLOCKED -> PlanStartAlarmDecision(
        startNormalAlert = false,
        cancelPendingAlerts = !deferred,
        showForegroundBlockedNotification = !deferred,
        showDeferredNotification = deferred,
        recordForegroundFailure = true,
    )
    else -> PlanStartAlarmDecision(
        startNormalAlert = !deferred,
        cancelPendingAlerts = false,
        showForegroundBlockedNotification = false,
        showDeferredNotification = deferred,
        recordForegroundFailure = false,
    )
}

/**
 * Shared START-alarm orchestration used by PlanAlarmReceiver and unit tests:
 * prevents silent swallowing when ContextCompat.startForegroundService is rejected.
 */
internal suspend fun executeStartAlarmHandling(
    mayStart: Boolean,
    deferred: Boolean,
    startForegroundService: () -> Unit,
    logFailure: (String, Throwable) -> Unit,
    recordFailure: (String) -> Unit,
    cancelPendingAlerts: () -> Unit,
    startNormalAlert: suspend () -> Unit,
    showForegroundBlockedNotification: () -> Unit,
    showDeferredNotification: () -> Unit,
): PlanStartAlarmDecision {
    var failureReason: String? = null
    val outcome = if (!mayStart) {
        StartForegroundOutcome.NOT_ATTEMPTED
    } else {
        try {
            startForegroundService()
            StartForegroundOutcome.STARTED
        } catch (error: Throwable) {
            if (!isForegroundStartRestrictedError(error)) throw error
            val reason = "${error::class.java.simpleName}: ${error.message ?: "foreground service start restricted"}"
            failureReason = reason
            logFailure(reason, error)
            StartForegroundOutcome.BLOCKED
        }
    }
    val decision = decidePlanStartAlarm(mayStart, deferred, outcome)
    if (decision.recordForegroundFailure) {
        recordFailure(requireNotNull(failureReason))
    }
    if (decision.cancelPendingAlerts) {
        cancelPendingAlerts()
    }
    if (decision.startNormalAlert) {
        startNormalAlert()
    }
    if (decision.showForegroundBlockedNotification) {
        showForegroundBlockedNotification()
    }
    if (decision.showDeferredNotification) {
        showDeferredNotification()
    }
    return decision
}
