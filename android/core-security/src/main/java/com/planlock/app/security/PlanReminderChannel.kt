package com.planlock.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings

/** Versioned channels copy user policy once; subsequent ensure calls never reset settings. */
object PlanReminderChannel {
    const val ID = "oriva_plan_waiting_v3"
    const val START_ID = "oriva_plan_start_v3"
    const val COMPLETION_ID = "oriva_plan_completed_v3"
    fun readinessChannelIds(): List<String> = listOf(ID, START_ID, COMPLETION_ID)

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val oldReminder = manager.getNotificationChannel("oriva_plan_reminders_v2")
        val oldEvents = manager.getNotificationChannel("oriva_plan_events")
        fun create(id: String, name: String, importance: Int, old: NotificationChannel?, selfPlay: Boolean = false) {
            if (manager.getNotificationChannel(id) != null) return
            val level = if (old != null && (Build.VERSION.SDK_INT < 29 || old.hasUserSetImportance())) old.importance else importance
            manager.createNotificationChannel(NotificationChannel(id, name, level).apply {
                description = "知序计划提醒"
                lockscreenVisibility = old?.lockscreenVisibility ?: Notification.VISIBILITY_PUBLIC
                setShowBadge(old?.canShowBadge() ?: true)
                enableLights(old?.shouldShowLights() ?: false)
                lightColor = old?.lightColor ?: 0
                enableVibration(old?.shouldVibrate() ?: true)
                old?.vibrationPattern?.let { vibrationPattern = it }
                setSound(old?.sound ?: if (old == null) RingtoneManager.getDefaultUri(if (selfPlay) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION) else null,
                    old?.audioAttributes ?: AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                if (manager.isNotificationPolicyAccessGranted) setBypassDnd(old?.canBypassDnd() ?: false)
            })
        }
        create(ID, "计划提前提醒", NotificationManager.IMPORTANCE_HIGH, oldReminder)
        create(START_ID, "计划开始闹钟", NotificationManager.IMPORTANCE_HIGH, oldReminder, selfPlay = true)
        create(COMPLETION_ID, "计划完成", NotificationManager.IMPORTANCE_DEFAULT, oldEvents)
        // Historical channels are retained as migration inputs and to preserve the user's policy audit trail.
    }

    fun canAlert(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        ensure(context)
        val channel = manager.getNotificationChannel(ID) ?: return false
        val blocked = Build.VERSION.SDK_INT >= 28 && channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        return !blocked && channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, ID)
}
