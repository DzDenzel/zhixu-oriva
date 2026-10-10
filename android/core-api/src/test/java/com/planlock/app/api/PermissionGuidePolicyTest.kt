package com.planlock.app.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGuidePolicyTest {
    @Test
    fun missingPermissionShowsWhenThereIsNoRecentDismissal() {
        assertTrue(PermissionGuidePolicy.shouldShow(true, null, nowMillis = 10_000L, notificationRequestInFlight = false))
    }

    @Test
    fun recentDismissalSuppressesTheGuide() {
        assertFalse(PermissionGuidePolicy.shouldShow(true, 10_000L, nowMillis = 10_000L + 23 * 60 * 60 * 1000L, notificationRequestInFlight = false))
    }

    @Test
    fun notificationRequestTakesPriorityOverTheGuide() {
        assertFalse(PermissionGuidePolicy.shouldShow(true, null, nowMillis = 10_000L, notificationRequestInFlight = true))
    }
}
