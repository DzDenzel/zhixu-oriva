package com.planlock.app.record

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ReminderDeliveryMode { EXACT, INEXACT }

class ScheduleReminderScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun deliveryMode(): ReminderDeliveryMode =
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) ReminderDeliveryMode.EXACT else ReminderDeliveryMode.INEXACT

    fun schedule(schedule: ScheduleSnapshotEntity, afterMillis: Long = System.currentTimeMillis()): Long? {
        cancel(schedule.id)
        val trigger = ScheduleReminderTime.nextTrigger(schedule, afterMillis, TimeZone.getDefault()) ?: return null
        val pending = pendingIntent(schedule.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return null
        if (deliveryMode() == ReminderDeliveryMode.EXACT) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
        return trigger
    }

    fun cancel(scheduleId: Long) {
        pendingIntent(scheduleId, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
    }

    private fun pendingIntent(scheduleId: Long, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        scheduleId.hashCode(),
        Intent(appContext, ScheduleReminderReceiver::class.java).putExtra(EXTRA_SCHEDULE_ID, scheduleId),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object { const val EXTRA_SCHEDULE_ID = "schedule_id" }
}

class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(ScheduleReminderScheduler.EXTRA_SCHEDULE_ID, Long.MIN_VALUE)
        if (scheduleId == Long.MIN_VALUE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = RecordStore(context)
                val schedule = store.schedule(scheduleId) ?: return@launch
                notify(context, schedule)
                ScheduleReminderScheduler(context).schedule(schedule, System.currentTimeMillis() + 60_000L)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, schedule: ScheduleSnapshotEntity) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "日程提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, CHANNEL)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(schedule.title)
            .setContentText(schedule.location?.let { "日程即将开始 · $it" } ?: "日程即将开始")
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(schedule.id.hashCode(), notification)
    }

    companion object { private const val CHANNEL = "planlock_schedule_reminders" }
}
