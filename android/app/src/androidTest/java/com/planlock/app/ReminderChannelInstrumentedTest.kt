package com.planlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.PlanReminderChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Use an isolated instrumentation target; never changes an installed user's channel settings. */
class ReminderChannelInstrumentedTest {
    @Test fun oldFeedbackChannelAndNewRemindersRemainIndependent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".testchannels")) { "Requires isolated testchannels app ID" }
        if (Build.VERSION.SDK_INT < 26) return@runBlocking
        val manager = context.getSystemService(NotificationManager::class.java)
        // A pre-upgrade DEFAULT channel cannot be promoted by recreate-with-HIGH.
        manager.createNotificationChannel(NotificationChannel("oriva_plan_events", "旧反馈", NotificationManager.IMPORTANCE_DEFAULT))
        val oldImportance = manager.getNotificationChannel("oriva_plan_events").importance
        TokenStore.restore(context.planLockPreferences)
        val plan = PlanSnapshotEntity(-137, 0, "渠道独立性", "学习", "06:00", "06:02", null, null, null, 1, null, 1, 0)
            .copy(scope = TokenStore.state.value.dataScope)
        val tag = "oriva-plan:${plan.scope}"
        try {
            PlanNotifications.reminder(context, plan)
            val channel = manager.getNotificationChannel(PlanReminderChannel.ID)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue(channel.shouldVibrate())
            assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
            val reminder = manager.activeNotifications.single { it.tag == tag && it.id == plan.id.hashCode() }.notification
            assertEquals(PlanReminderChannel.ID, reminder.channelId)
            assertEquals(Notification.PRIORITY_MAX, reminder.priority)
            assertTrue(PlanReminderChannel.canAlert(context))
            val settings = PlanReminderChannel.settingsIntent(context)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.action)
            assertEquals(PlanReminderChannel.ID, settings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            PlanNotifications.completed(context, plan, 120_000)
            assertEquals("oriva_plan_events", manager.activeNotifications.single { it.tag == tag && it.id == plan.id.hashCode() }.notification.channelId)
            assertEquals(oldImportance, manager.getNotificationChannel("oriva_plan_events").importance)
            // Lowering is permitted by Android when the user has not changed the channel.
            manager.createNotificationChannel(NotificationChannel(PlanReminderChannel.ID, "计划提前提醒", NotificationManager.IMPORTANCE_LOW))
            PlanReminderChannel.ensure(context)
            assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(PlanReminderChannel.ID).importance)
            assertFalse("Downgraded reminders must be reported as unable to pop up", PlanReminderChannel.canAlert(context))
        } finally {
            manager.cancel(tag, plan.id.hashCode())
        }
    }
}
