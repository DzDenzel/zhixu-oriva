package com.planlock.app

import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlanAlarmDispatchPolicyTest {

    @Test fun startSuccessNonDeferredStartsNormalAlertWithoutFailureRecord() = runBlocking {
        val events = mutableListOf<String>()
        val decision = executeStartAlarmHandling(
            mayStart = true,
            deferred = false,
            startForegroundService = { events += "fgs" },
            logFailure = { _, _ -> events += "log_failure" },
            recordFailure = { events += "record_failure:$it" },
            cancelPendingAlerts = { events += "cancel_alerts" },
            startNormalAlert = { events += "normal_alert" },
            showForegroundBlockedNotification = { events += "blocked_notice" },
            showDeferredNotification = { events += "deferred_notice" },
        )
        assertEquals(
            PlanStartAlarmDecision(
                startNormalAlert = true,
                cancelPendingAlerts = false,
                showForegroundBlockedNotification = false,
                showDeferredNotification = false,
                recordForegroundFailure = false,
            ),
            decision,
        )
        assertEquals(listOf("fgs", "normal_alert"), events)
    }

    @Test fun illegalStateOnStartRejectsSilentSwallowAndShowsHighVisibilityGuidance() = runBlocking {
        val events = mutableListOf<String>()
        var loggedError: Throwable? = null
        val decision = executeStartAlarmHandling(
            mayStart = true,
            deferred = false,
            startForegroundService = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") },
            logFailure = { reason, error ->
                loggedError = error
                events += "log:$reason"
            },
            recordFailure = { reason -> events += "record:$reason" },
            cancelPendingAlerts = { events += "cancel_alerts" },
            startNormalAlert = { events += "normal_alert" },
            showForegroundBlockedNotification = { events += "blocked_notice" },
            showDeferredNotification = { events += "deferred_notice" },
        )
        // 反例断言：禁止旧实现静默吞错（既不落状态也不发引导通知）
        assertFalse(
            "Silent swallow regression: blocked FGS must record failure and show guidance",
            !decision.recordForegroundFailure && !decision.showForegroundBlockedNotification,
        )
        assertTrue(decision.recordForegroundFailure)
        assertTrue(decision.showForegroundBlockedNotification)
        assertTrue(decision.cancelPendingAlerts)
        assertFalse(decision.startNormalAlert)
        assertFalse(decision.showDeferredNotification)
        assertTrue(loggedError is IllegalStateException)
        assertEquals(
            listOf(
                "log:IllegalStateException: ForegroundServiceStartNotAllowedException",
                "record:IllegalStateException: ForegroundServiceStartNotAllowedException",
                "cancel_alerts",
                "blocked_notice",
            ),
            events,
        )
    }

    @Test fun securityExceptionOnDeferredStartRecordsFailureAndUsesDeferredNotice() = runBlocking {
        val events = mutableListOf<String>()
        val decision = executeStartAlarmHandling(
            mayStart = true,
            deferred = true,
            startForegroundService = { throw SecurityException("bg fgs denied") },
            logFailure = { reason, _ -> events += "log:$reason" },
            recordFailure = { reason -> events += "record:$reason" },
            cancelPendingAlerts = { events += "cancel_alerts" },
            startNormalAlert = { events += "normal_alert" },
            showForegroundBlockedNotification = { events += "blocked_notice" },
            showDeferredNotification = { events += "deferred_notice" },
        )
        assertTrue(decision.recordForegroundFailure)
        assertFalse(decision.showForegroundBlockedNotification)
        assertTrue(decision.showDeferredNotification)
        assertFalse(decision.startNormalAlert)
        assertEquals(
            listOf(
                "log:SecurityException: bg fgs denied",
                "record:SecurityException: bg fgs denied",
                "deferred_notice",
            ),
            events,
        )
    }

    @Test fun outsideStartWindowDoesNotAttemptForegroundService() = runBlocking {
        val events = mutableListOf<String>()
        val decision = executeStartAlarmHandling(
            mayStart = false,
            deferred = true,
            startForegroundService = { events += "fgs" },
            logFailure = { _, _ -> events += "log" },
            recordFailure = { events += "record" },
            cancelPendingAlerts = { events += "cancel" },
            startNormalAlert = { events += "normal_alert" },
            showForegroundBlockedNotification = { events += "blocked_notice" },
            showDeferredNotification = { events += "deferred_notice" },
        )
        assertFalse(decision.recordForegroundFailure)
        assertFalse(decision.showForegroundBlockedNotification)
        assertTrue(decision.showDeferredNotification)
        assertEquals(listOf("deferred_notice"), events)
    }

    @Test fun nonRestrictedErrorIsNotSwallowed() {
        assertFalse(isForegroundStartRestrictedError(IllegalArgumentException("bad argument")))
        assertTrue(isForegroundStartRestrictedError(IllegalStateException("fgs blocked")))
        assertTrue(isForegroundStartRestrictedError(SecurityException("denied")))
    }

    @Test fun receiverActionClassificationRoutesExactAlarmPermissionAndSystemBroadcasts() {
        assertEquals(PlanAlarmBroadcastAction.ALARM_DELIVERY, classifyReceiverAction(null))
        assertFalse(shouldRescheduleAlarms(classifyReceiverAction(null)))

        val exactAction = ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        assertEquals(PlanAlarmBroadcastAction.EXACT_ALARM_PERMISSION_CHANGED, classifyReceiverAction(exactAction))
        assertTrue(shouldRescheduleAlarms(classifyReceiverAction(exactAction)))

        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        ).forEach { action ->
            assertEquals(PlanAlarmBroadcastAction.SYSTEM_RESCHEDULE, classifyReceiverAction(action))
            assertTrue(shouldRescheduleAlarms(classifyReceiverAction(action)))
        }

        assertEquals(PlanAlarmBroadcastAction.IGNORED, classifyReceiverAction("com.example.UNRELATED"))
        assertFalse(shouldRescheduleAlarms(classifyReceiverAction("com.example.UNRELATED")))
    }
}
