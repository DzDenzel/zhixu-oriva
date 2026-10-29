package com.planlock.app.record

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import com.planlock.app.api.TokenStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Optional, device-local calendar backup. No account sync, Room schema or background worker. */
class PlanCalendarFallback(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("plan_calendar_fallback", Context.MODE_PRIVATE)
    private val accountName = "ORIVA:${app.packageName}"

    fun isEnabled(): Boolean = preferences.getBoolean(ENABLED, false) && hasPermission()

    /** Includes deferred deletion after the switch was turned off; does not require permission. */
    fun needsReconcile(): Boolean = preferences.getBoolean(ENABLED, false) || preferences.getBoolean(CLEANUP, false)

    /** The caller owns the runtime permission request; this method never launches UI. */
    suspend fun enable(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!hasPermission()) return@withLock false
            try {
                if (preferences.getBoolean(CLEANUP, false)) deleteCalendars()
                val store = RecordStore(app)
                val scope = store.currentScope()
                val plans = store.plans()
                if (scope != TokenStore.state.value.dataScope) return@withLock false
                sync(scope, plans, System.currentTimeMillis())
                check(preferences.edit().putBoolean(ENABLED, true).putBoolean(CLEANUP, false).commit())
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                preferences.edit().putBoolean(ENABLED, false).putBoolean(CLEANUP, true).commit()
                // A partial enable must not leave live reminders behind when permission permits cleanup.
                try { if (hasPermission()) deleteCalendars() }
                catch (cleanupError: Exception) { Log.w(TAG, "Calendar enable rollback failed", cleanupError) }
                Log.w(TAG, "Calendar fallback enable failed", error)
                false
            }
        }
    }

    /** Revocation can prevent provider writes; pending cleanup is retried on reconcile/enable. */
    suspend fun disable(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            check(preferences.edit().putBoolean(ENABLED, false).putBoolean(CLEANUP, true).commit())
            if (!hasPermission()) return@withLock
            try { deleteCalendars() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w(TAG, "Calendar fallback cleanup failed", error) }
        }
    }

    /** Same lifecycle entry point as PlanAlarmScheduler.reconcile, including ID/scope changes. */
    suspend fun reconcile(store: RecordStore = RecordStore(app), now: Long = System.currentTimeMillis()) {
        if (!needsReconcile()) return
        val scope = store.currentScope()
        reconcile(scope, store.plans(), now)
    }

    /** Reuses the scheduler's already-loaded snapshot; call outside its synchronized section. */
    suspend fun reconcile(scope: String, plans: List<PlanSnapshotEntity>, now: Long = System.currentTimeMillis()): Unit =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!hasPermission() || scope != TokenStore.state.value.dataScope) return@withLock
                try {
                    if (preferences.getBoolean(CLEANUP, false)) deleteCalendars()
                    if (preferences.getBoolean(ENABLED, false)) sync(scope, plans, now)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w(TAG, "Calendar fallback reconciliation failed", error) }
            }
        }

    /** Call only after an on-time REMINDER, or START when no advance reminder exists. */
    suspend fun suppressOccurrence(planId: Long, occurrenceStartAt: Long, scope: String = TokenStore.state.value.dataScope): Unit =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!preferences.getBoolean(ENABLED, false) || scope != TokenStore.state.value.dataScope) return@withLock
                val key = PlanCalendarOccurrenceKey(scope, planId, occurrenceStartAt).encode()
                val suppressed = preferences.getStringSet(SUPPRESSED, emptySet()).orEmpty().toMutableSet()
                suppressed += key
                // Persist first: a process death must not restore this occurrence's reminder.
                check(preferences.edit().putStringSet(SUPPRESSED, suppressed).commit())
                if (!hasPermission()) return@withLock
                try {
                    for (calendarId in calendarIds()) {
                        resolver.query(syncUri(Events.CONTENT_URI), arrayOf(Events._ID),
                            "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?", arrayOf(calendarId.toString(), key), null)
                            ?.use { cursor -> while (cursor.moveToNext()) removeReminder(cursor.getLong(0)) }
                            ?: error("Calendar provider returned no event cursor")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Log.w(TAG, "Calendar reminder suppression failed", error) }
            }
        }

    private fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun calendarIds(): List<Long> = buildList {
        val cursor = resolver.query(syncUri(Calendars.CONTENT_URI), arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
            arrayOf(accountName, CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_NAME), null)
            ?: error("Calendar provider unavailable")
        cursor.use { while (it.moveToNext()) add(it.getLong(0)) }
    }

    private fun ensureCalendar(): Long {
        val ids = calendarIds()
        if (ids.isNotEmpty()) {
            // Recover even if a previous process died between calendar creation and preference commit.
            ids.drop(1).forEach { resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, it)), null, null) }
            return ids.first()
        }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, "知序 ORIVA 计划兜底")
            put(Calendars.CALENDAR_COLOR, 0xFF476B5D.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, accountName)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.MAX_REMINDERS, 1)
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(syncUri(Calendars.CONTENT_URI), values)))
    }

    private fun deleteCalendars() {
        calendarIds().forEach { id ->
            // CalendarProvider cascades events, reminders and instances for this calendar only.
            resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
        }
        check(preferences.edit().clear().putBoolean(ENABLED, false).putBoolean(CLEANUP, false).commit())
    }

    private fun sync(scope: String, plans: List<PlanSnapshotEntity>, now: Long) {
        val calendarId = ensureCalendar()
        val suppressed = preferences.getStringSet(SUPPRESSED, emptySet()).orEmpty()
        val desired = PlanCalendarFallbackPolicy.expand(scope, plans, now, suppressed)
        val existing = readEvents(calendarId)
        val diff = PlanCalendarFallbackPolicy.diff(existing, desired)
        diff.delete.forEach { resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, it.eventId)), null, null) }
        val mapping = existing.filter { it !in diff.delete }.associate { it.event.key.encode() to it.eventId }.toMutableMap()
        diff.update.forEach { (previous, event) -> writeEvent(calendarId, previous.eventId, event) }
        diff.insert.forEach { event -> mapping[event.key.encode()] = writeEvent(calendarId, null, event) }
        val activeKeys = desired.mapTo(mutableSetOf()) { it.key.encode() }
        val edit = preferences.edit().putStringSet(SUPPRESSED, suppressed.intersect(activeKeys))
        preferences.all.keys.filter { it.startsWith(EVENT_PREFIX) }.forEach(edit::remove)
        mapping.forEach { (key, id) -> edit.putLong(EVENT_PREFIX + key, id) }
        check(edit.commit())
    }

    private fun readEvents(calendarId: Long): List<PlanCalendarStoredEvent> {
        val result = mutableListOf<PlanCalendarStoredEvent>()
        val projection = arrayOf(Events._ID, Events._SYNC_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE)
        val cursor = resolver.query(syncUri(Events.CONTENT_URI), projection,
            "${Events.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null)
            ?: error("Calendar provider returned no event cursor")
        val invalid = mutableListOf<Long>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val key = PlanCalendarOccurrenceKey.decode(it.getString(1).orEmpty())
                if (key == null) { invalid += id; continue }
                result += PlanCalendarStoredEvent(id, PlanCalendarEvent(key, it.getString(2).orEmpty(),
                    it.getLong(3), it.getLong(4), it.getString(5).orEmpty(), null))
            }
        }
        invalid.forEach { resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, it)), null, null) }
        // Chunk the selection to stay below SQLite's bind-parameter limit without reading other calendars.
        val reminders = mutableMapOf<Long, MutableList<Pair<Int, Int>>>()
        result.map { it.eventId }.chunked(500).forEach { ids ->
            resolver.query(syncUri(Reminders.CONTENT_URI), arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray(), null)
                ?.use { rows -> while (rows.moveToNext()) {
                    reminders.getOrPut(rows.getLong(0)) { mutableListOf() } += rows.getInt(1) to rows.getInt(2)
                } } ?: error("Calendar provider returned no reminder cursor")
        }
        return result.map { stored ->
            val rows = reminders[stored.eventId].orEmpty()
            // -1 is an invalid desired value, forcing repair of duplicate/default/email reminders.
            val minutes = if (rows.isEmpty()) null else rows.singleOrNull()?.takeIf { it.second == Reminders.METHOD_ALERT }?.first ?: -1
            stored.copy(event = stored.event.copy(reminderMinutes = minutes))
        }
    }

    private fun eventValues(calendarId: Long, event: PlanCalendarEvent) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events._SYNC_ID, event.key.encode())
        put(Events.TITLE, event.title)
        put(Events.DESCRIPTION, "知序系统日历兜底；知序提醒准点送达后，本场日历提醒会自动取消。")
        put(Events.DTSTART, event.startAt)
        put(Events.DTEND, event.endAt)
        put(Events.EVENT_TIMEZONE, event.timeZone)
        put(Events.HAS_ALARM, if (event.reminderMinutes == null) 0 else 1)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
    }

    private fun writeEvent(calendarId: Long, eventId: Long?, event: PlanCalendarEvent): Long {
        val operations = arrayListOf<ContentProviderOperation>()
        if (eventId == null) {
            operations += ContentProviderOperation.newInsert(syncUri(Events.CONTENT_URI)).withValues(eventValues(calendarId, event)).build()
        } else {
            operations += ContentProviderOperation.newUpdate(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, eventId)))
                .withValues(eventValues(calendarId, event)).build()
            operations += ContentProviderOperation.newDelete(syncUri(Reminders.CONTENT_URI))
                .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString())).build()
        }
        if (event.reminderMinutes != null) {
            val reminder = ContentProviderOperation.newInsert(syncUri(Reminders.CONTENT_URI))
                .withValue(Reminders.MINUTES, event.reminderMinutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
            if (eventId == null) reminder.withValueBackReference(Reminders.EVENT_ID, 0)
            else reminder.withValue(Reminders.EVENT_ID, eventId)
            operations += reminder.build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return eventId ?: ContentUris.parseId(checkNotNull(results[0].uri))
    }

    private fun removeReminder(eventId: Long) {
        val operations = arrayListOf(
            ContentProviderOperation.newDelete(syncUri(Reminders.CONTENT_URI))
                .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString())).build(),
            ContentProviderOperation.newUpdate(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, eventId)))
                .withValue(Events.HAS_ALARM, 0).build(),
        )
        resolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }

    companion object {
        private val lock = Mutex()
        private const val TAG = "OrivaCalendar"
        private const val CALENDAR_NAME = "oriva_plan_fallback"
        private const val ENABLED = "enabled"
        private const val CLEANUP = "cleanup_pending"
        private const val SUPPRESSED = "suppressed"
        private const val EVENT_PREFIX = "event:"
    }
}
