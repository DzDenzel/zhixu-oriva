package com.planlock.app.record

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.planlock.app.api.TokenStore
import com.planlock.app.api.PlanTimingPreferences
import com.planlock.app.api.planLockPreferences
import kotlinx.coroutines.flow.first

/** One registration owner for local edits, sync writes, ID remapping and identity changes. */
class PlanAlarmScheduler(context: Context) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val registry = app.getSharedPreferences("scoped_plan_alarms", Context.MODE_PRIVATE)

    suspend fun reconcile(store: RecordStore = RecordStore(app), now: Long = System.currentTimeMillis()) {
        val scope = store.currentScope()
        val plans = store.plans()
        val punctualStart = PlanTimingPreferences.enabled(app.planLockPreferences).first()
        synchronized(lock) {
            if (scope != TokenStore.state.value.dataScope) return
            val desired = plans.flatMap { plan -> PlanAlarmKind.entries.mapNotNull { kind ->
                PlanAlarmTime.next(plan, kind, now)?.let { alarm -> Triple("$scope\n${plan.id}\n${kind.name}", plan.id, alarm) }
            } }.associateBy { it.first }
            registry.all.keys.filter { it !in desired }.forEach(::cancelKey)
            desired.values.forEach { (key, id, alarm) ->
                val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
                val mode = planAlarmDeliveryMode(alarm.kind, punctualStart, exact)
                val signature = "${alarm.triggerAt}:${mode.name}"
                if (registry.getString(key, null) == signature) return@forEach
                val intent = pending(scope, id, alarm.kind, PendingIntent.FLAG_UPDATE_CURRENT, alarm) ?: return@forEach
                val registeredMode = try {
                    when (mode) {
                        PlanAlarmDeliveryMode.ALARM_CLOCK -> alarms.setAlarmClock(
                            AlarmManager.AlarmClockInfo(alarm.triggerAt, showPending(scope, id, alarm.kind, PendingIntent.FLAG_UPDATE_CURRENT)), intent)
                        PlanAlarmDeliveryMode.EXACT -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, intent)
                        PlanAlarmDeliveryMode.INEXACT -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, intent)
                    }
                    mode
                } catch (_: SecurityException) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, intent)
                    PlanAlarmDeliveryMode.INEXACT
                }
                if (registeredMode != PlanAlarmDeliveryMode.ALARM_CLOCK) showPending(scope, id, alarm.kind, PendingIntent.FLAG_NO_CREATE)?.cancel()
                registry.edit().putString(key, "${alarm.triggerAt}:${registeredMode.name}").apply()
            }
            val manager = app.getSystemService(NotificationManager::class.java)
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
            val currentIds = plans.filter { it.status == 1 && !PlanOccurrence.isEnded(it, minutes, OccurrenceDate.from(calendar)) }.map { it.id.hashCode() }.toSet()
            manager.activeNotifications.filter { it.tag?.startsWith("oriva-plan:") == true &&
                (it.tag != "oriva-plan:$scope" || it.id !in currentIds) }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /** OS registrations disappear after boot; force registration even when signatures match. */
    fun invalidate() = synchronized(lock) { registry.all.keys.forEach(::cancelKey) }

    private fun cancelKey(key: String) {
        val parts = key.split('\n')
        if (parts.size >= 4) {
            val scope = parts.dropLast(2).joinToString("\n")
            val id = parts[parts.lastIndex - 1].toLongOrNull()
            val kind = runCatching { PlanAlarmKind.valueOf(parts.last()) }.getOrNull()
            if (id != null && kind != null) {
                pending(scope, id, kind, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
                showPending(scope, id, kind, PendingIntent.FLAG_NO_CREATE)?.cancel()
            }
        }
        registry.edit().remove(key).apply()
    }

    private fun pending(scope: String, id: Long, kind: PlanAlarmKind, flags: Int, alarm: PlanAlarm? = null): PendingIntent? =
        PendingIntent.getBroadcast(app, id.hashCode(), Intent().setClassName(app, "com.planlock.app.PlanAlarmReceiver")
            .setData(Uri.Builder().scheme("oriva").authority("plan-alarm").appendPath(scope).appendPath(id.toString()).appendPath(kind.name).build())
            .putExtra("scope", scope).putExtra("plan_id", id).putExtra("kind", kind.name)
            .putExtra("trigger_at", alarm?.triggerAt ?: 0L), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun showPending(scope: String, id: Long, kind: PlanAlarmKind, flags: Int): PendingIntent? =
        PendingIntent.getActivity(app, id.hashCode(), Intent().setClassName(app, "com.planlock.app.MainActivity")
            .setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(scope).appendPath(id.toString())
                .appendQueryParameter("alarmKind", kind.name).build())
            .putExtra("notification_scope", scope).putExtra("notification_plan_id", id), flags or PendingIntent.FLAG_IMMUTABLE)

    companion object { private val lock = Any() }
}
