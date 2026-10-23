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
import android.net.Uri
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
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
    private val registry = appContext.getSharedPreferences("scoped_schedule_alarms", Context.MODE_PRIVATE)

    fun deliveryMode(): ReminderDeliveryMode =
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) ReminderDeliveryMode.EXACT else ReminderDeliveryMode.INEXACT

    fun schedule(schedule: ScheduleSnapshotEntity, afterMillis: Long = System.currentTimeMillis()): Long? {
        if (schedule.scope != TokenStore.state.value.dataScope) return null
        cancel(schedule.id, schedule.scope)
        val trigger = ScheduleReminderTime.nextTrigger(schedule, afterMillis, TimeZone.getDefault()) ?: return null
        val pending = pendingIntent(schedule.id, schedule.scope, PendingIntent.FLAG_UPDATE_CURRENT) ?: return null
        registry.edit().putLong(schedule.scope + "\n" + schedule.id, schedule.id).apply()
        if (deliveryMode() == ReminderDeliveryMode.EXACT) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
        return trigger
    }

    fun cancel(scheduleId: Long, scope: String = TokenStore.state.value.dataScope) {
        pendingIntent(scheduleId, scope, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
        registry.edit().remove(scope + "\n" + scheduleId).apply()
    }

    fun cancelAll() {
        registry.all.forEach { (key, value) ->
            val id = value as? Long ?: return@forEach
            cancel(id, key.substringBeforeLast('\n'))
            appContext.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("oriva-schedule:") == true }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelLegacy(scheduleId: Long) {
        PendingIntent.getBroadcast(appContext, scheduleId.hashCode(), Intent(appContext, ScheduleReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let(alarmManager::cancel)
        appContext.getSystemService(NotificationManager::class.java).cancel(scheduleId.hashCode())
    }

    private fun pendingIntent(scheduleId: Long, scope: String, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        scheduleId.hashCode(),
        Intent(appContext, ScheduleReminderReceiver::class.java).setData(Uri.Builder().scheme("oriva").authority("reminder").appendPath(scope).appendPath(scheduleId.toString()).build())
            .putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_SCOPE, scope),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object { const val EXTRA_SCHEDULE_ID = "schedule_id"; const val EXTRA_SCOPE = "scope" }
}

class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(ScheduleReminderScheduler.EXTRA_SCHEDULE_ID, Long.MIN_VALUE)
        if (scheduleId == Long.MIN_VALUE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                com.planlock.app.api.TokenStore.restore(context.planLockPreferences)
                if (intent.getStringExtra(ScheduleReminderScheduler.EXTRA_SCOPE) != TokenStore.state.value.dataScope) return@launch
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
        if (schedule.scope != TokenStore.state.value.dataScope) return
        manager.notify("oriva-schedule:" + schedule.scope, schedule.id.hashCode(), notification)
    }

    companion object { private const val CHANNEL = "planlock_schedule_reminders" }
}
