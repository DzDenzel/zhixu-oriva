package com.planlock.app.engine

import com.planlock.app.record.ObservationSource
import com.planlock.app.security.AccessibilityHealthEvaluator
import com.planlock.app.security.AccessibilityRuntimeSignal

object ObservationSourcePolicy {
    fun select(signal: AccessibilityRuntimeSignal, nowMillis: Long): ObservationSource {
        val heartbeat = signal.heartbeatAtMillis
        val healthy = signal.hasEverReported && signal.running && heartbeat != null &&
            nowMillis - heartbeat in 0..AccessibilityHealthEvaluator.FRESHNESS_WINDOW_MILLIS
        return if (healthy) ObservationSource.ACCESSIBILITY else ObservationSource.USAGE_STATS_APPROXIMATE
    }
}
