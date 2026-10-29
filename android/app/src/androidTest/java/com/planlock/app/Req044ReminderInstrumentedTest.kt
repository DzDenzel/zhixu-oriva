package com.planlock.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.AlarmKind
import com.planlock.app.api.PlanLockPreferenceKeys
import com.planlock.app.api.PlanTimingPreferences
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.record.PlanAlarmDeliveryLog
import com.planlock.app.record.PlanAlarmKind
import com.planlock.app.record.PlanAlarmLateness
import com.planlock.app.record.PlanAlarmScheduler
import com.planlock.app.record.PlanAlarmTime
import com.planlock.app.record.PlanLockDatabase
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.PlanReminderChannel
import com.planlock.app.sync.AppSync
import java.util.Calendar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real OS notifications/alarms and device preferences; never runnable against the formal app ID. */
class Req044ReminderInstrumentedTest {
    @Test fun waitingCardIsCancelledWhenPlanIsDeleted() = waitingMutation("delete")

    @Test fun waitingCardIsCancelledWhenPlanIsDisabled() = waitingMutation("disable")

    @Test fun waitingCardIsCancelledWhenPlanTimeChanges() = waitingMutation("time-change")

    private fun qaContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.planlock.req044.qa") { "Requires isolated com.planlock.req044.qa target" }
        return context
    }

    private fun prerequisite(condition: Boolean, message: String) {
        if (!condition) Log.i(TAG, "SKIP: $message")
        assumeTrue(message, condition)
    }

    private fun waitingMutation(mutation: String) = runBlocking {
        val context = qaContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        prerequisite(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            "Grant POST_NOTIFICATIONS to the QA target before exercising waiting cards")
        prerequisite(manager.areNotificationsEnabled(), "QA notifications must be enabled")
        if (Build.VERSION.SDK_INT >= 26) {
            PlanReminderChannel.ensure(context)
            prerequisite(manager.getNotificationChannel(PlanReminderChannel.ID)?.importance != NotificationManager.IMPORTANCE_NONE,
                "QA waiting channel must not be blocked")
        }
        TokenStore.restore(context.planLockPreferences)
        AppSync.get(context).withLocalMutation {
            // Reconcile intentionally removes stale cards globally. Do not consume another probe's cards.
            prerequisite(manager.activeNotifications.none { it.tag?.startsWith("oriva-wait:") == true || it.tag?.startsWith("oriva-plan:") == true },
                "No other live plan cards may be present while using an in-memory plan store")
            val database = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
            val scope = TokenStore.state.value.dataScope
            val store = RecordStore(database, { scope })
            val scheduler = PlanAlarmScheduler(context)
            val registry = context.getSharedPreferences("scoped_plan_alarms", Context.MODE_PRIVATE)
            // Tomorrow avoids minute rollover/midnight races, while still posting a real future card.
            val now = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val plan = PlanSnapshotEntity(-2_190_440L, 0, "REQ044 $mutation QA", "study", "10:00", "11:00",
                null, null, null, 15, null, 1, 0, scope = scope)
            val tag = "oriva-wait:${Uri.encode(scope)}:${plan.id}"
            val createdNotificationIntents = mutableListOf<PendingIntent>()
            fun card() = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == plan.id.hashCode() }
            fun alarmPending(kind: PlanAlarmKind): PendingIntent? = PendingIntent.getBroadcast(context, plan.id.hashCode(),
                Intent().setClassName(context, "com.planlock.app.PlanAlarmReceiver")
                    .setData(Uri.Builder().scheme("oriva").authority("plan-alarm").appendPath(scope)
                        .appendPath(plan.id.toString()).appendPath(kind.name).build()),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            fun showPending(kind: PlanAlarmKind): PendingIntent? = PendingIntent.getActivity(context, plan.id.hashCode(),
                Intent().setClassName(context, "com.planlock.app.MainActivity")
                    .setData(Uri.Builder().scheme("oriva").authority("plan").appendPath(scope)
                        .appendPath(plan.id.toString()).appendQueryParameter("alarmKind", kind.name).build()),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            try {
                store.updateLocalPlan(plan)
                scheduler.reconcile(store, now)
                val before = registry.all.toMap()
                val expectedKeys = PlanAlarmKind.entries.map { "$scope\n${plan.id}\n${it.name}" }.toSet()
                assertEquals(expectedKeys, before.keys)
                PlanAlarmKind.entries.forEach { assertNotNull("Real broadcast registration for $it", alarmPending(it)) }
                val start = requireNotNull(PlanAlarmTime.next(plan, PlanAlarmKind.START, now))
                PlanLiveUpdates.waiting(context, plan, start.triggerAt, start.endAt)
                awaitState("Real waiting card was not posted") { card() != null }
                val posted = requireNotNull(card()).notification
                posted.contentIntent?.let(createdNotificationIntents::add)
                posted.deleteIntent?.let(createdNotificationIntents::add)
                assertEquals(start.triggerAt, posted.`when`)
                assertEquals("WAITING", posted.extras.getString("oriva_live_phase"))
                if (Build.VERSION.SDK_INT >= 26) assertEquals(PlanReminderChannel.ID, posted.channelId)
                // Establish that it is valid before the edit, not a pre-existing stale card.
                scheduler.reconcile(store, now)
                assertNotNull("Unchanged occurrence must keep its waiting card", card())
                val changed = when (mutation) {
                    "delete" -> { store.deletePlan(plan.id); null }
                    "disable" -> plan.copy(status = 0).also { store.updateLocalPlan(it) }
                    "time-change" -> plan.copy(startTime = "12:00", endTime = "13:00").also { store.updateLocalPlan(it) }
                    else -> error("Unknown fixture mutation")
                }
                val cancellationStarted = SystemClock.elapsedRealtime()
                scheduler.reconcile(store, now)
                // A bounded OS round trip, not waiting for the scheduled start/timeout.
                awaitState("Edited plan retained its waiting card") { card() == null }
                val cancellationMs = SystemClock.elapsedRealtime() - cancellationStarted
                if (mutation == "time-change") {
                    assertEquals(expectedKeys, registry.all.keys)
                    PlanAlarmKind.entries.forEach { kind ->
                        val key = "$scope\n${plan.id}\n${kind.name}"
                        val expected = requireNotNull(PlanAlarmTime.next(requireNotNull(changed), kind, now))
                        val signature = requireNotNull(registry.getString(key, null))
                        assertEquals("No stale trigger for $kind", expected.triggerAt, signature.substringBefore(':').toLong())
                        assertNotEquals("Old occurrence registration survived for $kind", before[key], signature)
                        assertNotNull(alarmPending(kind))
                        if (!signature.endsWith(":ALARM_CLOCK")) assertNull(showPending(kind))
                    }
                } else {
                    assertTrue("Deleted/disabled plan retained registry entries", registry.all.isEmpty())
                    PlanAlarmKind.entries.forEach { kind ->
                        assertNull("Stale OS broadcast for $kind", alarmPending(kind))
                        assertNull("Stale OS show intent for $kind", showPending(kind))
                    }
                }
                proof("waiting-$mutation", JSONObject().put("realWaitingCard", true).put("mutation", mutation)
                    .put("cancelledWithinMs", cancellationMs).put("oldTargetAt", start.triggerAt)
                    .put("before", JSONObject(before)).put("after", JSONObject(registry.all)))
            } finally {
                manager.cancel(tag, plan.id.hashCode())
                createdNotificationIntents.forEach { it.cancel() }
                try {
                    scheduler.invalidate()
                    // Restore durable QA plans' OS registrations; fixture mutations never entered their DB.
                    scheduler.reconcile(RecordStore(context))
                } finally {
                    database.close()
                    Log.i(TAG, "CLEANUP waiting-$mutation: fixture card/intents removed, durable QA alarms reconciled, in-memory DB closed")
                }
            }
        }
    }

    @Test fun deliveryLogRetainsLast20AndStrictDeferralBoundaryAcrossInstances() {
        val context = qaContext()
        val preferences = context.getSharedPreferences("plan_alarm_delivery", Context.MODE_PRIVATE)
        val hadLast20 = preferences.contains("last20")
        val original = preferences.getString("last20", null)
        val unrelated = preferences.all.filterKeys { it != "last20" }
        val expected = mutableListOf<AlarmDeferral>()
        val scheduledAt = 1_790_000_000_000L
        var sequence = 0L
        fun append(kind: PlanAlarmKind, lateMs: Long) {
            val item = PlanAlarmDeliveryLog(context).record(kind, -2_190_440L - sequence,
                "REQ044 log QA ${sequence++}", scheduledAt, scheduledAt + lateMs)
            assertEquals(AlarmKind.valueOf(kind.name), item.kind)
            assertEquals(lateMs, item.lateMs)
            expected.add(item)
            if (expected.size > 20) expected.removeAt(0)
            val reopened = PlanAlarmDeliveryLog(context)
            assertEquals("New instance must read committed device preferences", expected, reopened.entries())
            assertEquals(expected.lastOrNull { it.lateMs > PlanAlarmLateness.THRESHOLD_MS }, reopened.lastDeferral())
            val persisted = JSONArray(preferences.getString("last20", "[]"))
            assertEquals(expected.size, persisted.length())
            assertEquals(item.kind.name, persisted.getJSONObject(persisted.length() - 1).getString("kind"))
            assertEquals(lateMs, persisted.getJSONObject(persisted.length() - 1).getLong("lateMs"))
        }
        try {
            assertTrue(preferences.edit().remove("last20").commit())
            assertTrue(PlanAlarmDeliveryLog(context).entries().isEmpty())
            assertNull(PlanAlarmDeliveryLog(context).lastDeferral())
            // Check each real kind below/at/above the strict >60-second boundary, including early delivery.
            PlanAlarmKind.entries.forEach { kind ->
                listOf(-1L, 0L, 59_999L, 60_000L).forEach { append(kind, it) }
                val deferred = PlanAlarmDeliveryLog(context).lastDeferral()
                append(kind, 60_000L)
                assertEquals("Exactly 60 seconds must not become the latest deferral", deferred, PlanAlarmDeliveryLog(context).lastDeferral())
                append(kind, 60_001L)
                assertEquals(expected.last(), PlanAlarmDeliveryLog(context).lastDeferral())
            }
            repeat(25) { append(PlanAlarmKind.entries[it % PlanAlarmKind.entries.size], 60_001L + it) }
            assertEquals(20, expected.size)
            val retained = expected.map { it.planId }
            // Once the delayed records fall out of the last20, lastDeferral must become null.
            repeat(20) { append(PlanAlarmKind.entries[it % PlanAlarmKind.entries.size], 60_000L) }
            assertNull(PlanAlarmDeliveryLog(context).lastDeferral())
            assertEquals(unrelated, preferences.all.filterKeys { it != "last20" })
            proof("delivery-log", JSONObject().put("devicePreferences", "plan_alarm_delivery")
                .put("retention", 20).put("retainedIds", JSONArray(retained))
                .put("kinds", JSONArray(PlanAlarmKind.entries.map { it.name }))
                .put("boundaryLateMs", JSONArray(listOf(-1L, 0L, 59_999L, 60_000L, 60_001L)))
                .put("newInstancePersistence", true).put("evictedDeferralIsNull", true))
        } finally {
            val edit = preferences.edit()
            if (hadLast20) edit.putString("last20", original) else edit.remove("last20")
            assertTrue("Restore original device delivery history", edit.commit())
            Log.i(TAG, "CLEANUP delivery-log: original last20 restored; unrelated preference keys untouched")
        }
    }

    /** Requires a genuine pre-upgrade QA fixture, customized through Android channel settings. */
    @Test fun migrationCopiesUserChannelPolicyAndPreservesPunctualPreference() = runBlocking {
        val context = qaContext()
        // hasUserSetImportance starts at API29; hasUserSetSound starts at API30.
        prerequisite(Build.VERSION.SDK_INT >= 30, "Truthful user importance AND sound migration proof requires API30+")
        val manager = context.getSystemService(NotificationManager::class.java)
        val targets = PlanReminderChannel.readinessChannelIds()
        prerequisite(targets.all { manager.getNotificationChannel(it) == null },
            "Migration requires never-created v3 channels; deleting existing channels is NOT a truthful reset (Android resurrects policy)")
        val oldReminder = manager.getNotificationChannel("oriva_plan_reminders_v2")
        val oldEvents = manager.getNotificationChannel("oriva_plan_events")
        prerequisite(oldReminder != null && oldEvents != null, "Install a pre-upgrade QA fixture with both historical channels")
        val sources = listOf(requireNotNull(oldReminder), requireNotNull(oldEvents))
        prerequisite(sources.all { it.hasUserSetImportance() && it.hasUserSetSound() },
            "Both historical channels must have real Android user-set importance and sound flags; app-created values are not user customization")
        prerequisite(sources.all { it.importance != NotificationManager.IMPORTANCE_HIGH && it.sound == null && !it.shouldVibrate() },
            "In Android settings set both historical channels to non-HIGH importance, silent sound and vibration off; defaults do not prove policy copying")
        val original = context.planLockPreferences.data.first()[PlanLockPreferenceKeys.punctualPlanStart]
        val sourceSnapshots = sources.associate { it.id to channelPolicy(it) }
        try {
            PlanTimingPreferences.setEnabled(context.planLockPreferences, false)
            PlanReminderChannel.ensure(context)
            assertEquals(false, context.planLockPreferences.data.first()[PlanLockPreferenceKeys.punctualPlanStart])
            val pairs = listOf(PlanReminderChannel.ID to requireNotNull(oldReminder),
                PlanReminderChannel.START_ID to oldReminder, PlanReminderChannel.COMPLETION_ID to requireNotNull(oldEvents))
            pairs.forEach { (id, old) ->
                val migrated = requireNotNull(manager.getNotificationChannel(id))
                assertEquals("Migrated importance for $id", old.importance, migrated.importance)
                assertEquals("Migrated sound for $id", old.sound, migrated.sound)
                assertEquals("Migrated vibration for $id", old.shouldVibrate(), migrated.shouldVibrate())
                assertArrayEquals("Migrated vibration pattern for $id", old.vibrationPattern, migrated.vibrationPattern)
                assertEquals("Migrated audio attributes for $id", old.audioAttributes, migrated.audioAttributes)
            }
            val migratedSnapshots = targets.associateWith { channelPolicy(requireNotNull(manager.getNotificationChannel(it))) }
            PlanTimingPreferences.setEnabled(context.planLockPreferences, true)
            PlanReminderChannel.ensure(context)
            assertEquals(true, context.planLockPreferences.data.first()[PlanLockPreferenceKeys.punctualPlanStart])
            assertEquals(migratedSnapshots, targets.associateWith { channelPolicy(requireNotNull(manager.getNotificationChannel(it))) })
            assertEquals(sourceSnapshots, sources.associate { it.id to channelPolicy(requireNotNull(manager.getNotificationChannel(it.id))) })
            proof("channel-migration", JSONObject().put("api", Build.VERSION.SDK_INT)
                .put("genuineUserSetImportanceAndSound", true).put("sourcePolicy", JSONObject(sourceSnapshots))
                .put("migratedPolicy", JSONObject(migratedSnapshots)).put("punctualFalsePreserved", true)
                .put("punctualTruePreserved", true).put("repeatEnsurePreservesPolicy", true))
        } finally {
            context.planLockPreferences.edit {
                if (original == null) it.remove(PlanLockPreferenceKeys.punctualPlanStart)
                else it[PlanLockPreferenceKeys.punctualPlanStart] = original
            }
            // QA-only cleanup. Deleted channel IDs cannot be reused as fresh migration inputs.
            targets.forEach { manager.deleteNotificationChannel(it) }
            Log.i(TAG, "CLEANUP migration: punctual key restored, created v3 channels deleted; historical user channels unchanged. Reinstall fresh QA fixture before rerun")
        }
    }

    private fun channelPolicy(channel: android.app.NotificationChannel): String = JSONObject()
        .put("importance", channel.importance).put("sound", channel.sound?.toString() ?: JSONObject.NULL)
        .put("vibration", channel.shouldVibrate())
        .put("vibrationPattern", channel.vibrationPattern?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        .put("audioAttributes", channel.audioAttributes?.toString() ?: JSONObject.NULL).toString()

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue(message, condition())
    }

    private fun proof(case: String, details: JSONObject) {
        Log.i(TAG, JSONObject().put("case", case).put("assertionsPassed", true).put("proof", details).toString())
    }

    companion object { private const val TAG = "Req044ReminderTest" }
}
