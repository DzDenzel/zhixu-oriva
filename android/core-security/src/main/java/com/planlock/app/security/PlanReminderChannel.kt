package com.planlock.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** One stable, new ID separates advance reminders from existing plan feedback preferences. */
object PlanReminderChannel {
    const val ID = "oriva_plan_reminders_v2"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ID, "计划提前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "计划开始前的横幅与锁屏提醒"
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    fun canAlert(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        ensure(context)
        val channel = manager.getNotificationChannel(ID) ?: return false
        val groupBlocked = Build.VERSION.SDK_INT >= 28 && channel.group?.let {
            manager.getNotificationChannelGroup(it)?.isBlocked
        } == true
        return !groupBlocked && channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun settingsIntent(context: Context): Intent {
        ensure(context)
        return Intent(if (Build.VERSION.SDK_INT >= 26) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ID)
    }
}
