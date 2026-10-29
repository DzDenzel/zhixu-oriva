package com.planlock.app.record

import android.content.Context
import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.AlarmKind
import org.json.JSONArray
import org.json.JSONObject

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
    fun entries(): List<AlarmDeferral> = synchronized(lock) { read() }
    fun lastDeferral(): AlarmDeferral? = entries().lastOrNull { it.lateMs > PlanAlarmLateness.THRESHOLD_MS }
    private fun read(): List<AlarmDeferral> = runCatching {
        val array = JSONArray(preferences.getString("last20", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            AlarmDeferral(AlarmKind.valueOf(it.getString("kind")), it.getLong("planId"), it.getString("planName"), it.getLong("scheduledAt"), it.getLong("deliveredAt"))
        } }
    }.getOrDefault(emptyList())
    companion object { private val lock = Any() }
}

object PlanAlarmLateness {
    const val THRESHOLD_MS = 60_000L
    fun isDeferred(scheduledAt: Long, deliveredAt: Long): Boolean = deliveredAt - scheduledAt > THRESHOLD_MS
    fun mayRemind(scheduledAt: Long, deliveredAt: Long, startAt: Long, endAt: Long): Boolean =
        deliveredAt < endAt && (!isDeferred(scheduledAt, deliveredAt) || deliveredAt < startAt)
    fun mayStart(deliveredAt: Long, startAt: Long, endAt: Long): Boolean = deliveredAt >= startAt && deliveredAt < endAt
    fun delayedMinutes(lateMs: Long): Long = (lateMs / 60_000L).coerceAtLeast(1)
}
