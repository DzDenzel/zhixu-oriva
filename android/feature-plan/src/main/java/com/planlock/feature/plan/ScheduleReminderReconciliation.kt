package com.planlock.feature.plan

/**
 * A new offline schedule first owns a negative local id. When a successful
 * upload replaces it with the server id, the alarm keyed by the provisional
 * id must be cancelled before the refreshed server snapshot is scheduled.
 */
internal fun shouldCancelProvisionalReminder(
    localId: Long,
    localScheduleStillExists: Boolean,
): Boolean = localId < 0 && !localScheduleStillExists
