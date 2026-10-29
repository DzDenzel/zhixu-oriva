package com.planlock.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.PlanAlarmTime
import com.planlock.app.record.PlanAlarmKind
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.PlanReminderChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object PlanAlertSoundPolicy {
    fun sound(ringerMode: Int, filter: Int, planOwnedDnd: Boolean, verified: Boolean = true): Boolean =
        verified && ringerMode == AudioManager.RINGER_MODE_NORMAL && (filter == NotificationManager.INTERRUPTION_FILTER_ALL || planOwnedDnd)
    fun vibrate(ringerMode: Int, filter: Int, planOwnedDnd: Boolean, verified: Boolean = true): Boolean =
        verified && ringerMode != AudioManager.RINGER_MODE_SILENT && (filter == NotificationManager.INTERRUPTION_FILTER_ALL || planOwnedDnd)
    fun maySnooze(count: Int): Boolean = count < 3
}

/** Ringing belongs to the active execution service process; no extra persistent service is started. */
object PlanAlerts {
    private var ringtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringingKey: String? = null
    private fun key(scope: String, id: Long) = "${Uri.encode(scope)}:$id"
    private fun prefs(context: Context) = context.getSharedPreferences("plan_alert_actions", Context.MODE_PRIVATE)
    fun detail(context: Context, scope: String, id: Long): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(scope).appendPath(id.toString()).build())
        .putExtra("notification_scope", scope).putExtra("notification_plan_id", id)
    fun action(context: Context, scope: String, id: Long, endAt: Long, snooze: Boolean): Intent =
        Intent(context, PlanAlertActionReceiver::class.java).setData(Uri.parse("oriva://alert-action/${Uri.encode(scope)}/$id/$endAt/$snooze"))
            .putExtra("scope", scope).putExtra("plan_id", id).putExtra("end_at", endAt).putExtra("snooze", snooze)

    suspend fun start(context: Context, plan: PlanSnapshotEntity, endAt: Long, repeated: Boolean = false) {
        if (plan.scope != TokenStore.state.value.dataScope) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        PlanReminderChannel.ensure(context)
        val p = prefs(context)
        val token = key(plan.scope, plan.id)
        if (!repeated && p.getLong("occurrence:$token", 0L) == endAt) return
        if (!repeated) p.edit().putLong("occurrence:$token", endAt).putInt("snoozes:$token", 0).commit()
        val open = PendingIntent.getActivity(context, plan.id.hashCode(), Intent(context, PlanAlertActivity::class.java)
            .setData(Uri.parse("oriva://plan-alert/${Uri.encode(plan.scope)}/${plan.id}/$endAt"))
            .putExtra("scope", plan.scope).putExtra("plan_id", plan.id).putExtra("plan_name", plan.name).putExtra("end_at", endAt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val acknowledge = PendingIntent.getActivity(context, plan.id.hashCode(), detail(context, plan.scope, plan.id)
            .putExtra("acknowledge_alarm", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, plan.id.hashCode(), action(context, plan.scope, plan.id, endAt, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, PlanReminderChannel.START_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("「${plan.name}」开始了")
            .setContentText("计划照常进行 · 请选择提醒操作").setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setSilent(true).setContentIntent(open).setFullScreenIntent(open, true)
            .addAction(0, "5 分钟后再提醒", snooze).addAction(0, "我知道了", acknowledge).build()
        PlanLiveUpdates.cancelWaiting(context, plan)
        manager.notify("oriva-start:${plan.scope}", plan.id.hashCode(), notification)
        val state = context.planLockPreferences.data.first()
        val owned = state[PlanLockPreferenceKeys.dndPlanId] == plan.id && state[PlanLockPreferenceKeys.dndPreviousFilter] == NotificationManager.INTERRUPTION_FILTER_ALL && manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(PlanReminderChannel.START_ID) else null
        val allowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 || channel?.importance != NotificationManager.IMPORTANCE_NONE)
        if (allowed) ring(context, token, owned, channel)
        android.util.Log.i("OrivaAlerts", "START plan=${plan.id} end_at=$endAt full_screen=true can_use_fsi=${Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()} at=${System.currentTimeMillis()}")
    }
    @Synchronized private fun ring(context: Context, token: String, owned: Boolean, channel: android.app.NotificationChannel?) {
        stopRing()
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        ringingKey = token
        if (PlanAlertSoundPolicy.sound(mode, filter, owned) && (Build.VERSION.SDK_INT < 26 || channel?.sound != null)) {
            ringtone = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, if (Build.VERSION.SDK_INT >= 26) channel?.sound ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare()
                start()
            }
        }
        if (PlanAlertSoundPolicy.vibrate(mode, filter, owned) && (Build.VERSION.SDK_INT < 26 || channel?.shouldVibrate() == true)) {
            vibrator = context.getSystemService(Vibrator::class.java).also {
                if (Build.VERSION.SDK_INT >= 26) it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
                else @Suppress("DEPRECATION") it.vibrate(longArrayOf(0, 700, 500), 0)
            }
        }
    }
    @Synchronized fun stopRing() { ringtone?.let { it.stop(); it.release() }; ringtone = null; vibrator?.cancel(); vibrator = null; ringingKey = null }
    fun cancel(context: Context, scope: String, id: Long) {
        PlanAlertActivity.close(scope, id)
        if (ringingKey == key(scope, id)) stopRing()
        context.getSystemService(NotificationManager::class.java).cancel("oriva-start:$scope", id.hashCode())
        snoozePending(context, scope, id, 0L, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
    }
    fun acknowledge(context: Context, scope: String, id: Long) { if (scope == TokenStore.state.value.dataScope) cancel(context, scope, id) }
    fun snooze(context: Context, scope: String, id: Long, endAt: Long) {
        if (scope != TokenStore.state.value.dataScope || prefs(context).getLong("occurrence:${key(scope,id)}", 0L) != endAt) return
        cancel(context, scope, id)
        val p = prefs(context)
        val count = p.getInt("snoozes:${key(scope,id)}", 0)
        if (!PlanAlertSoundPolicy.maySnooze(count)) return
        p.edit().putInt("snoozes:${key(scope,id)}", count + 1).commit()
        val at = System.currentTimeMillis() + 5 * 60_000L
        if (at >= endAt) return
        val pending = requireNotNull(snoozePending(context, scope, id, endAt, PendingIntent.FLAG_UPDATE_CURRENT, at))
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pending)
    }
    private fun snoozePending(context: Context, scope: String, id: Long, endAt: Long, flags: Int, triggerAt: Long = 0L): PendingIntent? = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, PlanSnoozeReceiver::class.java).setData(Uri.parse("oriva://snooze/${Uri.encode(scope)}/$id"))
            .putExtra("scope", scope).putExtra("plan_id", id).putExtra("end_at", endAt).putExtra("trigger_at", triggerAt), flags or PendingIntent.FLAG_IMMUTABLE)
}
class PlanAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra("scope") ?: return
        val id = intent.getLongExtra("plan_id", Long.MIN_VALUE)
        if (intent.getBooleanExtra("snooze", false)) PlanAlerts.snooze(context, owner, id, intent.getLongExtra("end_at", 0))
        else PlanAlerts.acknowledge(context, owner, id)
    }
}
class PlanSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TokenStore.restore(context.planLockPreferences)
                if (intent.getStringExtra("scope") != TokenStore.state.value.dataScope) return@launch
                val plan = RecordStore(context).plan(intent.getLongExtra("plan_id", Long.MIN_VALUE)) ?: return@launch
                val end = intent.getLongExtra("end_at", 0)
                val now = System.currentTimeMillis()
                val due = intent.getLongExtra("trigger_at", 0)
                val delivery = com.planlock.app.record.PlanAlarmDeliveryLog(context).record(PlanAlarmKind.START, plan.id, plan.name, due, now)
                val deferred = com.planlock.app.record.PlanAlarmLateness.isDeferred(due, now)
                val occurrence = PlanAlarmTime.next(plan, PlanAlarmKind.END, now)
                if (deferred) PlanNotifications.deferred(context, plan, delivery.lateMs)
                if (occurrence?.endAt == end && now >= occurrence.startAt) {
                    androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java))
                    if (!deferred) PlanAlerts.start(context, plan, end, repeated = true)
                }
            } finally { result.finish() }
        }
    }
}
