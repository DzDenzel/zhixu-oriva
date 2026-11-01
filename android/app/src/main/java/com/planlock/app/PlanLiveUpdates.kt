package com.planlock.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.planlock.app.api.TokenStore
import com.planlock.app.record.entity.PlanSnapshotEntity

/** System-rendered countdown only: no per-second app timer or repeated notification refresh. */
object PlanLiveUpdates {
    private const val WAIT_CHANNEL = com.planlock.app.security.PlanReminderChannel.ID
    private const val FOREGROUND_CHANNEL = "planlock-exec"
    private const val FOREGROUND_ID = 1
    private const val PHASE = "oriva_live_phase"
    private fun waitTag(owner: String, id: Long) = "oriva-wait:${Uri.encode(owner)}:$id"
    private fun dismissKey(owner: String, id: Long) = "dismissed:${Uri.encode(owner)}:$id"
    fun waiting(context: Context, plan: PlanSnapshotEntity, startAt: Long, endAt: Long, detail: String) =
        post(context, plan, startAt, endAt, running = false, detail)
    fun running(context: Context, plan: PlanSnapshotEntity, endAt: Long, detail: String) {
        cancelWaiting(context, plan)
        post(context, plan, endAt, endAt, running = true, detail)
    }
    fun cancelWaiting(context: Context, plan: PlanSnapshotEntity) {
        context.getSystemService(NotificationManager::class.java).cancel(waitTag(plan.scope, plan.id), plan.id.hashCode())
    }
    @Synchronized private fun post(context: Context, plan: PlanSnapshotEntity, targetAt: Long, endAt: Long, running: Boolean, detail: String) {
        if (plan.scope != TokenStore.state.value.dataScope || targetAt <= System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val occurrence = PlanLiveIdentity.occurrence(plan.scope, plan.id, endAt)
        val preferences = context.getSharedPreferences("plan_live_dismissals", Context.MODE_PRIVATE)
        if (!PlanLiveIdentity.mayPublish(preferences.getString(dismissKey(plan.scope, plan.id), null), occurrence)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val stage = if (running) "RUNNING" else "WAITING"
        val tag = if (running) null else waitTag(plan.scope, plan.id)
        val id = if (running) FOREGROUND_ID else plan.id.hashCode()
        val displayTitle = if (running) PlanNotificationCopy.runningTitle(plan.name) else PlanNotificationCopy.waitingTitle(plan.name, targetAt - System.currentTimeMillis())
        if (manager.activeNotifications.any { it.tag == tag && it.id == id && it.notification.`when` == targetAt &&
                it.notification.extras.getString(PHASE) == stage && it.notification.extras.getString("oriva_occurrence") == occurrence &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == displayTitle }) return
        com.planlock.app.security.PlanReminderChannel.ensure(context)
        val open = PendingIntent.getActivity(context, plan.id.hashCode(), Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(plan.scope).appendPath(plan.id.toString()).build())
            .putExtra("notification_scope", plan.scope).putExtra("notification_plan_id", plan.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deleted = PendingIntent.getBroadcast(context, plan.id.hashCode(), Intent(context, PlanLiveDismissReceiver::class.java)
            .setData(Uri.Builder().scheme("oriva").authority("plan-live-dismiss").appendPath(occurrence).build())
            .putExtra("scope", plan.scope).putExtra("plan_id", plan.id).putExtra("end_at", endAt).putExtra("occurrence", occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mayAlert = !running && PlanAlertSoundPolicy.vibrate(context.getSystemService(AudioManager::class.java).ringerMode,
            manager.currentInterruptionFilter, planOwnedDnd = false)
        val channel = if (running) FOREGROUND_CHANNEL else WAIT_CHANNEL
        val publicVersion = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_oriva)
            .setContentTitle(if (running) PlanNotificationCopy.publicRunning else PlanNotificationCopy.publicWaiting)
            .setWhen(targetAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).build()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_oriva)
            .setContentTitle(displayTitle)
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).setDeleteIntent(deleted).setWhen(targetAt).setShowWhen(true)
            .setUsesChronometer(true).setChronometerCountDown(true).setColorized(false)
            .setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setSilent(!mayAlert)
            .addExtras(Bundle().apply { putString(PHASE, stage); putString("oriva_occurrence", occurrence) })
        if (running) {
            val endIntent = PlanAlerts.detail(context, plan.scope, plan.id).putExtra("end_plan", true)
            endIntent.data = endIntent.data?.buildUpon()?.appendQueryParameter("action", "end")?.build()
            builder.addAction(0, "结束", PendingIntent.getActivity(context, plan.id.hashCode(), endIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (!running) builder.setTimeoutAfter((targetAt - System.currentTimeMillis() + 5_000).coerceAtLeast(1))
        var notification = builder.build()
        var characteristics: Boolean? = null
        var profile = "REQUEST_PROMOTED"
        var canPost: Boolean? = null
        if (Build.VERSION.SDK_INT >= 36) {
            characteristics = runCatching { notification.hasPromotableCharacteristics() }.getOrNull()
            // Public API feature probe accommodates base API36's colorized requirement and later API36 variants.
            if (characteristics == false) {
                val legacy = builder.setColorized(true).build()
                if (runCatching { legacy.hasPromotableCharacteristics() }.getOrNull() == true) {
                    notification = legacy; characteristics = true; profile = "API36_COLORIZED_COMPAT"
                } else notification = builder.setColorized(false).build()
            }
            canPost = runCatching { manager.canPostPromotedNotifications() }.getOrNull()
        }
        // RUNNING updates the existing execution-service notification instead of creating a duplicate card.
        if (running) manager.notify(FOREGROUND_ID, notification) else manager.notify(tag, id, notification)
        val promoted = if (Build.VERSION.SDK_INT >= 36) manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            ?.notification?.let { it.flags and Notification.FLAG_PROMOTED_ONGOING != 0 } else false
        Log.i("OrivaLive", "phase=$stage target_at=$targetAt profile=$profile can_post=$canPost characteristics=$characteristics promoted_initial=$promoted")
    }
    @Synchronized fun dismissed(context: Context, owner: String, id: Long, endAt: Long, token: String) {
        if (owner != TokenStore.state.value.dataScope || id == Long.MIN_VALUE || token != PlanLiveIdentity.occurrence(owner, id, endAt)) return
        context.getSharedPreferences("plan_live_dismissals", Context.MODE_PRIVATE).edit().putString(dismissKey(owner, id), token).apply()
        Log.i("OrivaLive", "dismissal_saved")
    }
}
class PlanLiveDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra("scope") ?: return
        val token = intent.getStringExtra("occurrence") ?: return
        PlanLiveUpdates.dismissed(context, owner, intent.getLongExtra("plan_id", Long.MIN_VALUE), intent.getLongExtra("end_at", 0L), token)
    }
}
