package com.planlock.app

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Restores a user-approved binding; does not enable notification access or inspect notification contents. */
class NotificationListenerRecoveryInstrumentedTest {
    @Test fun approvedButDisconnectedListenerCanRebindAndApplyPendingSilence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        check(com.planlock.app.security.CapabilityAccess(context).check(com.planlock.app.security.Capability.NOTIFICATION_SILENCE))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun facts(): String {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys notification"))
                .bufferedReader().use { it.readText() }
            val live = dump.substringAfter("Live notification listeners", "").substringBefore("Snoozed notification listeners")
            return live.lines().filter { context.packageName in it }.joinToString("\n") + "\n" +
                dump.lines().filter { "mListenerHints:" in it }.joinToString("\n")
        }
        val before = facts()
        var after = before
        try {
            NotificationSilenceController.setRequired(true)
            // Production entry point, not an independent test-only rebind call.
            NotificationSilenceController.refreshConnection()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                SystemClock.sleep(200)
                after = facts()
                if (context.packageName in after && Regex("mListenerHints: [1-9]").containsMatchIn(after)) break
            } while (SystemClock.elapsedRealtime() < deadline)
            context.filesDir.resolve("listener-rebind-proof.txt").writeText("BEFORE\n$before\nAFTER\n$after\n", Charsets.UTF_8)
            assertTrue("Approved listener did not reconnect", context.packageName in after)
            assertTrue("Pending silence was not applied", Regex("mListenerHints: [1-9]").containsMatchIn(after))
        } finally {
            NotificationSilenceController.setRequired(false)
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (!facts().contains("mListenerHints: 0") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("Silence was not released", facts().contains("mListenerHints: 0"))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun cancelledPendingSilenceDoesNotMuteAfterReconnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.planlock.qapack3a")
        if (android.os.Build.VERSION.SDK_INT < 34) {
            org.junit.Assume.assumeTrue("Disconnect fixture requires API 34", false)
            return
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
        NotificationSilenceController.setRequired(false)
        NotificationListenerService.requestUnbind(ComponentName(context, FocusNotificationListenerService::class.java))
        fun live(): Boolean {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys notification"))
                .bufferedReader().use { it.readText() }
            return context.packageName in dump.substringAfter("Live notification listeners", "").substringBefore("Snoozed notification listeners")
        }
        val disconnectedDeadline = SystemClock.elapsedRealtime() + 3_000
        while (live() && SystemClock.elapsedRealtime() < disconnectedDeadline) SystemClock.sleep(100)
        assertFalse("Disconnect fixture was not established", live())
        SystemClock.sleep(10_000) // Separate lifecycle opportunity, not a product retry timer.
        NotificationSilenceController.setRequired(true)
        NotificationSilenceController.setRequired(false)
        val reconnectDeadline = SystemClock.elapsedRealtime() + 15_000
        while (!live() && SystemClock.elapsedRealtime() < reconnectDeadline) SystemClock.sleep(100)
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys notification"))
            .bufferedReader().use { it.readText() }
        val liveListeners = dump.substringAfter("Live notification listeners", "").substringBefore("Snoozed notification listeners")
        assertTrue("Listener did not reconnect", context.packageName in liveListeners)
        assertTrue("Cancelled requirement was reapplied", dump.contains("mListenerHints: 0"))
        } finally {
            NotificationSilenceController.setRequired(false)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
