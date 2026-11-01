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
import androidx.core.content.edit
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
}

/** CHANGE-063：再提醒只属于提前提醒；5 分钟后若已到开始时刻就不再推迟，由开始闹钟接手。 */
object PlanReminderSnooze {
    const val STEP_MS = 5 * 60_000L
    /** 下一次提前提醒的时刻；null = 不推迟（开始闹钟会在开始时刻响）。 */
    fun next(now: Long, startAt: Long): Long? = (now + STEP_MS).takeIf { it < startAt }
}

/** 浮窗阶段：提前提醒（可再提醒）与到点开始（只有「我知道了」）。 */
enum class PlanAlertPhase(val tag: String) { REMINDER("oriva-remind"), START("oriva-start") }

/**
 * CHANGE-063：提前提醒与到点开始都是闹钟式浮窗——不点按就持续响铃，锁屏为全屏页；任一按钮只停铃并收起浮窗，
 * 状态栏保留胶囊（等待 / 进行中），完整卡片在下拉通知栏。响铃属于正在运行的进程，不另起常驻服务。
 */
object PlanAlerts {
    private var ringtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringingKey: String? = null
    private fun key(scope: String, id: Long) = "${Uri.encode(scope)}:$id"
    private fun prefs(context: Context) = context.getSharedPreferences("plan_alert_actions", Context.MODE_PRIVATE)
    fun detail(context: Context, scope: String, id: Long): Intent = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(scope).appendPath(id.toString()).build())
        .putExtra("notification_scope", scope).putExtra("notification_plan_id", id)
    fun action(context: Context, scope: String, id: Long, startAt: Long, snooze: Boolean): Intent =
        Intent(context, PlanAlertActionReceiver::class.java).setData(Uri.parse("oriva://alert-action/${Uri.encode(scope)}/$id/$startAt/$snooze"))
            .putExtra("scope", scope).putExtra("plan_id", id).putExtra("start_at", startAt).putExtra("snooze", snooze)

    /** 提前提醒浮窗；repeated = 再提醒到点。 */
    suspend fun remind(context: Context, plan: PlanSnapshotEntity, startAt: Long, repeated: Boolean = false) =
        present(context, plan, PlanAlertPhase.REMINDER, startAt, startAt, requiresOwnPolicyBypass = false, repeated)

    suspend fun start(context: Context, plan: PlanSnapshotEntity, endAt: Long, requiresOwnPolicyBypass: Boolean) {
        // 开始闹钟取代提前提醒：收起提醒浮窗并取消尚未到点的再提醒
        dismiss(context, plan.scope, plan.id, PlanAlertPhase.REMINDER)
        cancelSnooze(context, plan.scope, plan.id)
        present(context, plan, PlanAlertPhase.START, System.currentTimeMillis(), endAt, requiresOwnPolicyBypass, repeated = false)
    }

    private suspend fun present(context: Context, plan: PlanSnapshotEntity, phase: PlanAlertPhase, startAt: Long, until: Long,
        requiresOwnPolicyBypass: Boolean, repeated: Boolean) {
        if (plan.scope != TokenStore.state.value.dataScope) return
        val now = System.currentTimeMillis()
        if (until <= now) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        PlanReminderChannel.ensure(context)
        val p = prefs(context)
        val token = key(plan.scope, plan.id)
        val once = "${phase.name}:$token"
        if (!repeated && p.getLong(once, 0L) == until) return
        if (!repeated) p.edit { putLong(once, until) }
        val store = RecordStore(context)
        val strategy = store.strategy(plan.id)
        val detailLine = planNotificationDetail(store, plan)
        val reminder = phase == PlanAlertPhase.REMINDER
        val title = if (reminder) PlanNotificationCopy.waitingTitle(plan.name, startAt - now) else PlanNotificationCopy.startTitle(plan.name)
        val open = PendingIntent.getActivity(context, plan.id.hashCode(), Intent(context, PlanAlertActivity::class.java)
            .setData(Uri.parse("oriva://plan-alert/${phase.name}/${Uri.encode(plan.scope)}/${plan.id}/$until"))
            .putExtra("scope", plan.scope).putExtra("plan_id", plan.id).putExtra("plan_name", plan.name)
            .putExtra("phase", phase.name).putExtra("start_at", startAt).putExtra("until", until).putExtra("plan_detail", detailLine)
            .putExtra("quiet_hint", PlanNotificationCopy.quietHint(strategy?.autoDnd == true, strategy?.notifySilence == true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun broadcast(snooze: Boolean) = PendingIntent.getBroadcast(context, plan.id.hashCode() * 31 + if (snooze) 1 else 0,
            action(context, plan.scope, plan.id, startAt, snooze), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val state = context.planLockPreferences.data.first()
        val filter = manager.currentInterruptionFilter
        val owned = !reminder && state[PlanLockPreferenceKeys.dndPlanId] == plan.id &&
            state[PlanLockPreferenceKeys.dndPreviousFilter] == NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        // On API37 the SILENT instance flag suppresses full-screen presentation too.
        // Only user-owned DND requests silence; normal and plan-owned alarms remain interruptive.
        val userDnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && !owned
        val publicVersion = NotificationCompat.Builder(context, PlanReminderChannel.START_ID).setSmallIcon(R.drawable.ic_stat_oriva)
            .setContentTitle(if (reminder) PlanNotificationCopy.publicWaiting else PlanNotificationCopy.publicStarted).build()
        val builder = NotificationCompat.Builder(context, PlanReminderChannel.START_ID)
            .setSmallIcon(R.drawable.ic_stat_oriva).setContentTitle(title)
            .setContentText(detailLine).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setOngoing(true).setSilent(userDnd).setContentIntent(open).setFullScreenIntent(open, true)
            .setTimeoutAfter(until - now)
        if (reminder) builder.addAction(0, "5 分钟后再提醒", broadcast(snooze = true))
        val notification = builder.addAction(0, "我知道了", broadcast(snooze = false)).build()
        val selfPlayback = requiresOwnPolicyBypass || owned
        if (!selfPlayback && !userDnd) notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        manager.notify("${phase.tag}:${plan.scope}", plan.id.hashCode(), notification)
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(PlanReminderChannel.START_ID) else null
        val allowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 || channel?.importance != NotificationManager.IMPORTANCE_NONE)
        if (allowed && selfPlayback) ring(context, token, owned, channel)
        android.util.Log.i("OrivaAlerts", "${phase.name} plan=${plan.id} until=$until full_screen=true can_use_fsi=${Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()} at=${System.currentTimeMillis()}")
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

    /** 收起一个阶段的浮窗：停铃、关全屏页、撤卡片；状态栏胶囊（等待 / 进行中）不动。 */
    private fun dismiss(context: Context, scope: String, id: Long, phase: PlanAlertPhase) {
        if (ringingKey == key(scope, id)) stopRing()
        PlanAlertActivity.close(scope, id, phase)
        context.getSystemService(NotificationManager::class.java).cancel("${phase.tag}:$scope", id.hashCode())
    }

    /** 计划结束 / 切换 / 失效时：两个阶段都收起，未到点的再提醒取消。 */
    fun cancel(context: Context, scope: String, id: Long) {
        PlanAlertPhase.entries.forEach { dismiss(context, scope, id, it) }
        cancelSnooze(context, scope, id)
    }

    /** 「我知道了」：只停铃收起，不打开应用、不解锁（CHANGE-063）。 */
    fun acknowledge(context: Context, scope: String, id: Long) {
        if (scope == TokenStore.state.value.dataScope) PlanAlertPhase.entries.forEach { dismiss(context, scope, id, it) }
    }

    /**
     * 「5 分钟后再提醒」：收起提前提醒浮窗；5 分钟后仍未开始则再响一次，否则不推迟并告诉用户还有几分钟开始。
     * 返回给界面的一句提示；null 表示已排好再提醒。
     */
    fun snooze(context: Context, scope: String, id: Long, startAt: Long): String? {
        if (scope != TokenStore.state.value.dataScope) return null
        dismiss(context, scope, id, PlanAlertPhase.REMINDER)
        val now = System.currentTimeMillis()
        val at = PlanReminderSnooze.next(now, startAt)
            ?: return PlanNotificationCopy.startsSoon(startAt - now).also {
                android.util.Log.i("OrivaAlerts", "SNOOZE plan=$id blocked_by_start start_at=$startAt at=$now")
            }
        val pending = requireNotNull(snoozePending(context, scope, id, startAt, PendingIntent.FLAG_UPDATE_CURRENT, at))
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pending)
        android.util.Log.i("OrivaAlerts", "SNOOZE plan=$id next=$at start_at=$startAt at=$now")
        return null
    }

    private fun cancelSnooze(context: Context, scope: String, id: Long) {
        snoozePending(context, scope, id, 0L, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
    }

    private fun snoozePending(context: Context, scope: String, id: Long, startAt: Long, flags: Int, triggerAt: Long = 0L): PendingIntent? = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, PlanSnoozeReceiver::class.java).setData(Uri.parse("oriva://snooze/${Uri.encode(scope)}/$id"))
            .putExtra("scope", scope).putExtra("plan_id", id).putExtra("start_at", startAt).putExtra("trigger_at", triggerAt), flags or PendingIntent.FLAG_IMMUTABLE)
}

/** 通知卡片上的两个按钮。广播在主线程，提示用 Toast（文字 Toast 允许后台显示）。 */
class PlanAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra("scope") ?: return
        val id = intent.getLongExtra("plan_id", Long.MIN_VALUE)
        if (intent.getBooleanExtra("snooze", false)) {
            PlanAlerts.snooze(context, owner, id, intent.getLongExtra("start_at", 0))
                ?.let { android.widget.Toast.makeText(context.applicationContext, it, android.widget.Toast.LENGTH_LONG).show() }
        } else PlanAlerts.acknowledge(context, owner, id)
    }
}

/** 再提醒到点：计划仍是这一场且未开始时再响一次提前提醒；已到开始时刻则交给开始闹钟。 */
class PlanSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                TokenStore.restore(context.planLockPreferences)
                if (intent.getStringExtra("scope") != TokenStore.state.value.dataScope) return@launch
                val store = RecordStore(context)
                val plan = store.plan(intent.getLongExtra("plan_id", Long.MIN_VALUE)) ?: return@launch
                val startAt = intent.getLongExtra("start_at", 0)
                val now = System.currentTimeMillis()
                val due = intent.getLongExtra("trigger_at", 0)
                com.planlock.app.record.PlanAlarmDeliveryLog(context).record(PlanAlarmKind.REMINDER, plan.id, plan.name, due, now)
                val occurrence = PlanAlarmTime.next(plan, PlanAlarmKind.START, now)
                if (occurrence?.startAt == startAt && now < startAt) {
                    // 再响时刷新等待胶囊标题（「N 分钟后开始」），否则还停在第一次提醒时的分钟数
                    PlanLiveUpdates.waiting(context, plan, startAt, occurrence.endAt, planNotificationDetail(store, plan))
                    PlanAlerts.remind(context, plan, startAt, repeated = true)
                }
            } finally { result.finish() }
        }
    }
}
