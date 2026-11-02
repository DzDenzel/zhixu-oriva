package com.planlock.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.FeedbackKind
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.*
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.app.security.PlanReminderChannel
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Locale

/** Visibility follows actual started Activities, including notification and configuration entry points. */
object AppVisibility { @Volatile var startedActivities: Int = 0; val foreground get() = startedActivities > 0 }

class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(9_000) {
                TokenStore.restore(context.planLockPreferences)
                val scheduler = PlanAlarmScheduler(context)
                if (intent.action != null) {
                    com.planlock.app.security.PlanReminderChannel.ensure(context)
                    scheduler.invalidate()
                    scheduler.reconcile()
                    if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
                        ExecutionService.recoverAfterReplacement(context)
                    return@withTimeout
                }
                AppSync.get(context).withLocalMutation {
                    val owner = intent.getStringExtra("scope") ?: return@withLocalMutation
                    if (owner != TokenStore.state.value.dataScope) return@withLocalMutation
                    val store = RecordStore(context)
                    val plan = store.plan(intent.getLongExtra("plan_id", Long.MIN_VALUE)) ?: return@withLocalMutation
                    val kind = runCatching { PlanAlarmKind.valueOf(intent.getStringExtra("kind").orEmpty()) }.getOrNull() ?: return@withLocalMutation
                    val due = intent.getLongExtra("trigger_at", 0)
                    val now = System.currentTimeMillis()
                    val delivery = PlanAlarmDeliveryLog(context).record(kind, plan.id, plan.name, due, now)
                    val deferred = PlanAlarmLateness.isDeferred(due, now)
                    android.util.Log.i("OrivaAlarms", "delivery kind=$kind plan_id=${plan.id} trigger_at=$due delivered_at=$now late_ms=${delivery.lateMs}")
                    val expected = PlanAlarmTime.next(plan, kind, due - 1)
                    if (expected?.triggerAt == due && now >= due) {
                        val fallback = PlanCalendarFallback(context)
                        if (now - due <= 5_000L && fallback.isEnabled() &&
                            (kind == PlanAlarmKind.REMINDER || kind == PlanAlarmKind.START))
                            fallback.suppressOccurrence(plan.id, expected.startAt, owner)
                        when (kind) {
                            PlanAlarmKind.REMINDER -> if (PlanAlarmLateness.mayRemind(due, now, expected.startAt, expected.endAt)) {
                                // CHANGE-063：等待胶囊常驻（静音），闹钟式浮窗负责响铃，点按后只剩胶囊
                                PlanLiveUpdates.waiting(context, plan, expected.startAt, expected.endAt, planNotificationDetail(store, plan))
                                PlanAlerts.remind(context, plan, expected.startAt)
                            }
                            PlanAlarmKind.START -> {
                                PlanLiveUpdates.cancelWaiting(context, plan)
                                if (PlanAlarmLateness.mayStart(now, expected.startAt, expected.endAt)) {
                                    runCatching { ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java)) }
                                        .onFailure { android.util.Log.w("OrivaAlarms", "Execution service start deferred", it) }
                                    if (!deferred) {
                                        val strategy = store.strategy(plan.id)
                                        PlanAlerts.start(context, plan, expected.endAt, strategy?.notifySilence == true || strategy?.autoDnd == true)
                                    }
                                }
                                if (deferred) PlanNotifications.deferred(context, plan, delivery.lateMs)
                            }
                            PlanAlarmKind.END -> PlanCompletion.finish(context, store, plan, now)
                        }
                    }
                    scheduler.reconcile(store)
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("OrivaAlarms", "Plan alarm delivery failed", error) }
            finally { pending.finish() }
        }
    }
}

object PlanCompletion {
    suspend fun finish(context: Context, store: RecordStore, plan: PlanSnapshotEntity, now: Long): Boolean {
        val start = store.activeExecution(plan.id) ?: return false
        val began = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
            .parse(start.startedAt.orEmpty().take(19))?.time }.getOrNull() ?: now
        val end = PlanAlarmTime.next(plan, PlanAlarmKind.END, began - 1)?.endAt
        val completed = plan.status == 1 && end != null && now >= end
        if (!store.finishOccurrence(plan.id, now)) return false
        if (com.planlock.app.engine.ExecutionEngine.activePlanId() == plan.id) com.planlock.app.engine.ExecutionEngine.stop()
        PlanAlerts.cancel(context, plan.scope, plan.id)
        PlanLiveUpdates.cancelWaiting(context, plan)
        val quiet = PlanCompletionGate.beforeNotification(context, store, plan, now)
        if (completed) {
            if (AppVisibility.foreground) ExecutionFeedback.emit(plan.id, FeedbackKind.COMPLETED)
            PlanNotifications.completed(context, plan, (requireNotNull(end) - began).coerceAtLeast(0), quiet, planNotificationDetail(store, plan))
            if (quiet == PlanQuietOutcome.RELEASED) executionHaptic(context)
        }
        return true
    }
}

object PlanNotifications {
    fun completed(context: Context, plan: PlanSnapshotEntity, durationMillis: Long, quiet: PlanQuietOutcome, detail: String) {
        val copy = PlanNotificationCopy.completed(plan.name, durationMillis, detail)
        show(context, plan, copy, PlanNotificationCopy.publicCompleted, quiet == PlanQuietOutcome.RELEASED)
        android.util.Log.i("OrivaAlerts", "END notify plan_id=${plan.id} quiet=$quiet at=${System.currentTimeMillis()}")
    }
    fun deferred(context: Context, plan: PlanSnapshotEntity, lateMs: Long) {
        show(context, plan, PlanNotificationCopy.deferred(plan.name, lateMs),
            PlanNotificationCopy.publicDeferred, audible = true, settings = true)
    }
    private fun show(context: Context, plan: PlanSnapshotEntity, copy: PlanNotificationText, publicTitle: String, audible: Boolean, settings: Boolean = false) {
        if (plan.scope != TokenStore.state.value.dataScope) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        PlanReminderChannel.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val tap = PendingIntent.getActivity(context, plan.id.hashCode(), PlanAlerts.detail(context, plan.scope, plan.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        val canAlert = audible && PlanAlertSoundPolicy.vibrate(mode, manager.currentInterruptionFilter, false)
        val publicVersion = NotificationCompat.Builder(context, PlanReminderChannel.COMPLETION_ID).setSmallIcon(R.drawable.ic_stat_oriva)
            .setContentTitle(publicTitle).build()
        val builder = NotificationCompat.Builder(context, PlanReminderChannel.COMPLETION_ID).setSmallIcon(R.drawable.ic_stat_oriva)
            .setContentTitle(copy.title).setContentText(copy.body).setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
            .setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).setContentIntent(tap).setSilent(!canAlert)
        if (settings) builder.addAction(0, "去设置", PendingIntent.getActivity(context, plan.id.hashCode(),
            Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("oriva://reminder-settings"))
                .putExtra("reminder_settings", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.notify("oriva-${if (settings) "deferred" else "completed"}:${plan.scope}", plan.id.hashCode(), builder.build())
    }
}
