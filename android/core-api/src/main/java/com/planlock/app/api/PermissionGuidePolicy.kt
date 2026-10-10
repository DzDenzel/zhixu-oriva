package com.planlock.app.api

object PermissionGuidePolicy {
    private const val DISMISS_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

    fun shouldShow(
        hasMissingPermissions: Boolean,
        dismissedAt: Long?,
        nowMillis: Long,
        notificationRequestInFlight: Boolean
    ): Boolean = hasMissingPermissions &&
        !notificationRequestInFlight &&
        (dismissedAt == null || nowMillis - dismissedAt >= DISMISS_INTERVAL_MILLIS)
}
