package com.planlock.app.security

import android.content.Context

enum class AccessibilityHealth {
    NOT_CONFIGURED,
    CONFIGURED_NOT_RUNNING,
    HEALTHY,
}

data class AccessibilityRuntimeSignal(
    val hasEverReported: Boolean = false,
    val running: Boolean = false,
    val heartbeatAtMillis: Long? = null,
)

object AccessibilityHealthEvaluator {
    const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
    const val FRESHNESS_WINDOW_MILLIS = 45_000L

    fun evaluateConfirmed(configured: Boolean, signal: AccessibilityRuntimeSignal, nowMillis: Long): AccessibilityHealth {
        if (!configured) return AccessibilityHealth.NOT_CONFIGURED
        if (!signal.hasEverReported || signal.heartbeatAtMillis == null) return AccessibilityHealth.CONFIGURED_NOT_RUNNING
        return evaluate(configured, signal, nowMillis)
    }

    fun evaluate(
        configured: Boolean,
        signal: AccessibilityRuntimeSignal,
        nowMillis: Long,
    ): AccessibilityHealth {
        if (!configured) return AccessibilityHealth.NOT_CONFIGURED
        if (!signal.hasEverReported || signal.heartbeatAtMillis == null) return AccessibilityHealth.HEALTHY
        val fresh = nowMillis - signal.heartbeatAtMillis in 0..FRESHNESS_WINDOW_MILLIS
        return if (signal.running && fresh) AccessibilityHealth.HEALTHY else AccessibilityHealth.CONFIGURED_NOT_RUNNING
    }
}

class AccessibilityHealthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun markRunning(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putBoolean(KEY_EVER_REPORTED, true)
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_HEARTBEAT_AT, nowMillis)
            .apply()
    }

    fun markStopped() {
        preferences.edit()
            .putBoolean(KEY_EVER_REPORTED, true)
            .putBoolean(KEY_RUNNING, false)
            .apply()
    }

    fun read(): AccessibilityRuntimeSignal = AccessibilityRuntimeSignal(
        hasEverReported = preferences.getBoolean(KEY_EVER_REPORTED, false),
        running = preferences.getBoolean(KEY_RUNNING, false),
        heartbeatAtMillis = preferences.getLong(KEY_HEARTBEAT_AT, 0L).takeIf { it > 0L },
    )

    companion object {
        private const val FILE_NAME = "accessibility_health"
        private const val KEY_EVER_REPORTED = "ever_reported"
        private const val KEY_RUNNING = "running"
        private const val KEY_HEARTBEAT_AT = "heartbeat_at"
    }
}
