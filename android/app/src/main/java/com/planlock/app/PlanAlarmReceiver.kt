package com.planlock.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
                    scheduler.invalidate()
                    scheduler.reconcile()
                    return@withTimeout
                }
                AppSync.get(context).withLocalMutation {
                    val owner = intent.getStringExtra("scope") ?: return@withLocalMutation
                    if (owner != TokenStore.state.value.dataScope) return@withLocalMutation
                    val store = RecordStore(context)
                    val plan = store.plan(intent.getLongExtra("plan_id", Long.MIN_VALUE)) ?: return@withLocalMutation
                    val kind = runCatching { PlanAlarmKind.valueOf(intent.getStringExtra("kind").orEmpty()) }.getOrNull() ?: return@withLocalMutation
                    val due = intent.getLongExtra("trigger_at", 0)
                    val expected = PlanAlarmTime.next(plan, kind, due - 1)
                    val now = System.currentTimeMillis()
                    if (expected?.triggerAt == due && now >= due) when (kind) {
                        PlanAlarmKind.REMINDER -> if (now < expected.endAt) PlanNotifications.reminder(context, plan)
                        PlanAlarmKind.START -> if (now < expected.endAt) {
                            // Exact alarm delivery is an allowed background foreground-service entry point.
                            runCatching { ContextCompat.startForegroundService(context, Intent(context, ExecutionService::class.java)) }
                                .onFailure { android.util.Log.w("OrivaAlarms", "Execution service start deferred", it) }
                        }
                        PlanAlarmKind.END -> PlanCompletion.finish(context, store, plan, now)
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
        if (completed) {
            if (AppVisibility.foreground) ExecutionFeedback.emit(plan.id, FeedbackKind.COMPLETED)
            else PlanNotifications.completed(context, plan, (requireNotNull(end) - began).coerceAtLeast(0))
            executionHaptic(context)
        }
        return true
    }
}

object PlanNotifications {
    private const val CHANNEL = "oriva_plan_events"
    suspend fun reminder(context: Context, plan: PlanSnapshotEntity) {
        val typeName = RecordStore(context).forScope(plan.scope).planTypeLabel(plan.type)
        val copy = PlanNotificationCopy.reminder(plan, typeName)
        PlanReminderChannel.ensure(context)
        show(context, plan, copy.title, copy.body, reminder = true)
    }
    fun completed(context: Context, plan: PlanSnapshotEntity, durationMillis: Long) {
        val copy = PlanNotificationCopy.completed(plan.name, durationMillis)
        show(context, plan, copy.title, copy.body)
    }

    private fun show(context: Context, plan: PlanSnapshotEntity, title: String, text: String, reminder: Boolean = false) {
        if (plan.scope != TokenStore.state.value.dataScope) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!reminder && Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "计划提醒与完成", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(context, MainActivity::class.java).putExtra("notification_plan_id", plan.id).putExtra("notification_scope", plan.scope)
            .setData(android.net.Uri.Builder().scheme("oriva").authority("plan").appendPath(plan.scope).appendPath(plan.id.toString()).build())
        val tap = PendingIntent.getActivity(context, plan.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, if (reminder) PlanReminderChannel.ID else CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply { if (reminder) {
                setPriority(NotificationCompat.PRIORITY_MAX)
                setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            } }
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).setContentIntent(tap).build()
        if (plan.scope == TokenStore.state.value.dataScope) manager.notify("oriva-plan:${plan.scope}", plan.id.hashCode(), notification)
    }
}

