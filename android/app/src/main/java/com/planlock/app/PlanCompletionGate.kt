package com.planlock.app

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.planLockPreferences
import com.planlock.app.dnd.DndController
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** Caller owns the existing withLocalMutation boundary; this helper never reacquires it. */
object PlanCompletionGate {
    suspend fun beforeNotification(context: Context, store: RecordStore, finished: PlanSnapshotEntity, now: Long): PlanQuietOutcome {
        return try {
            withTimeoutOrNull(2_200L) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val preferences = context.planLockPreferences.data.first()
                val hadOwnedDnd = preferences[PlanLockPreferenceKeys.dndPlanId] != null
                val previousUserFilter = preferences[PlanLockPreferenceKeys.dndPreviousFilter]
                val needsHostProbe = store.strategy(finished.id)?.notifySilence == true || NotificationSilenceController.isRequired()
                val releaseTicket = if (needsHostProbe) NotificationSilenceController.prepareReleaseReceipt() else null
                var needsFilterRestore = false
                val outcome = PlanQuietReleaseGate.await(
                    reconcile = {
                        val clock = Calendar.getInstance().apply { timeInMillis = now }
                        val minute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
                        val date = OccurrenceDate.from(clock)
                        val selected = store.plans().filter {
                            it.id != finished.id && it.status == 1 && PlanOccurrence.isActive(it, minute, date)
                        }.maxByOrNull { it.id }?.takeIf { store.canBeginOccurrence(it.id, now) }
                        val strategy = selected?.let { store.strategy(it.id) }
                        val quiet = strategy?.notifySilence == true
                        val neededDnd = selected?.id?.takeIf { strategy?.autoDnd == true }
                        NotificationSilenceController.setRequired(quiet)
                        val dnd = DndController(context).reconcile(neededDnd)
                        needsFilterRestore = hadOwnedDnd && neededDnd == null && previousUserFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                        Log.i("OrivaQuiet", "reconciled other_plan=${selected?.id} quiet=$quiet dnd=$dnd")
                        quiet || neededDnd != null
                    },
                    probe = {
                        val hints = if (needsHostProbe) NotificationSilenceController.currentHostHints() else 0
                        if (needsHostProbe && releaseTicket == null || hints == null) null
                        else if (needsHostProbe && !NotificationSilenceController.releaseReceiptConfirmed(requireNotNull(releaseTicket))) false else {
                            val hostReleased = hints and NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS == 0
                            hostReleased && (!needsFilterRestore || manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                        }
                    },
                    elapsed = SystemClock::elapsedRealtime,
                    pause = { delay(25) },
                )
                Log.i("OrivaQuiet", "end_gate=$outcome host_probe=$needsHostProbe filter=${manager.currentInterruptionFilter}")
                outcome
            } ?: PlanQuietOutcome.UNVERIFIED.also { Log.w("OrivaQuiet", "end_gate=UNVERIFIED timeout") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("OrivaQuiet", "end_gate=UNVERIFIED", error)
            PlanQuietOutcome.UNVERIFIED
        }
    }
}
