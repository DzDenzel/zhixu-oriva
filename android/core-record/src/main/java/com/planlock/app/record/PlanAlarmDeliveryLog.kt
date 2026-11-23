package com.planlock.app.record

import android.content.Context
import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.AlarmKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

@Serializable
data class PlanForegroundStartFailure(
    val planId: Long,
    val planName: String,
    val scheduledAt: Long,
    val failedAt: Long,
    val deferred: Boolean,
    val reason: String,
)

internal object PlanForegroundStartFailureCodec {
    private const val MAX_ENTRIES = 20
    private val json = Json { ignoreUnknownKeys = true }

    fun append(rawJson: String, failure: PlanForegroundStartFailure): Pair<List<PlanForegroundStartFailure>, String> {
        val items = (decode(rawJson) + failure).takeLast(MAX_ENTRIES)
        return items to encode(items)
    }

    fun decode(rawJson: String): List<PlanForegroundStartFailure> = runCatching {
        json.decodeFromString<List<PlanForegroundStartFailure>>(rawJson)
    }.getOrDefault(emptyList())

    fun encode(items: List<PlanForegroundStartFailure>): String = json.encodeToString(items)
}

/** Device-only diagnostics. No account scope and no sync/export integration. */
class PlanAlarmDeliveryLog(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plan_alarm_delivery", Context.MODE_PRIVATE)
    fun record(kind: PlanAlarmKind, planId: Long, planName: String, scheduledAt: Long, deliveredAt: Long): AlarmDeferral = synchronized(lock) {
        val item = AlarmDeferral(AlarmKind.valueOf(kind.name), planId, planName, scheduledAt, deliveredAt)
        val items = (read() + item).takeLast(20)
        preferences.edit().putString("last20", JSONArray().apply {
            items.forEach { add -> put(JSONObject().put("kind", add.kind.name).put("planId", add.planId)
                .put("planName", add.planName).put("scheduledAt", add.scheduledAt).put("deliveredAt", add.deliveredAt).put("lateMs", add.lateMs)) }
        }.toString()).commit()
        item
    }
    fun recordForegroundStartFailure(
        planId: Long,
        planName: String,
        scheduledAt: Long,
        failedAt: Long,
        deferred: Boolean,
        reason: String,
    ): PlanForegroundStartFailure = synchronized(lock) {
        val failure = PlanForegroundStartFailure(planId, planName, scheduledAt, failedAt, deferred, reason)
        val (_, encoded) = PlanForegroundStartFailureCodec.append(preferences.getString(FGS_FAILURES_KEY, "[]").orEmpty(), failure)
        preferences.edit().putString(FGS_FAILURES_KEY, encoded).commit()
        failure
    }
    fun entries(): List<AlarmDeferral> = synchronized(lock) { read() }
    fun lastDeferral(): AlarmDeferral? = entries().lastOrNull { it.lateMs > PlanAlarmLateness.THRESHOLD_MS }
    fun foregroundStartFailures(): List<PlanForegroundStartFailure> = synchronized(lock) {
        PlanForegroundStartFailureCodec.decode(preferences.getString(FGS_FAILURES_KEY, "[]").orEmpty())
    }
    fun lastForegroundStartFailure(): PlanForegroundStartFailure? = foregroundStartFailures().lastOrNull()
    private fun read(): List<AlarmDeferral> = runCatching {
        val array = JSONArray(preferences.getString("last20", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            AlarmDeferral(AlarmKind.valueOf(it.getString("kind")), it.getLong("planId"), it.getString("planName"), it.getLong("scheduledAt"), it.getLong("deliveredAt"))
        } }
    }.getOrDefault(emptyList())
    companion object {
        private val lock = Any()
        private const val FGS_FAILURES_KEY = "fgs_failures_last20"
    }
}

object PlanAlarmLateness {
    const val THRESHOLD_MS = 60_000L
    fun isDeferred(scheduledAt: Long, deliveredAt: Long): Boolean = deliveredAt - scheduledAt > THRESHOLD_MS
    fun mayRemind(scheduledAt: Long, deliveredAt: Long, startAt: Long, endAt: Long): Boolean =
        deliveredAt < endAt && (!isDeferred(scheduledAt, deliveredAt) || deliveredAt < startAt)
    fun mayStart(deliveredAt: Long, startAt: Long, endAt: Long): Boolean = deliveredAt >= startAt && deliveredAt < endAt
    fun delayedMinutes(lateMs: Long): Long = (lateMs / 60_000L).coerceAtLeast(1)
}
