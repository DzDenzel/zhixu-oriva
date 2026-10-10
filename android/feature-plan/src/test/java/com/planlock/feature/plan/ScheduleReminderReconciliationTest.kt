package com.planlock.feature.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleReminderReconciliationTest {
    @Test
    fun `cancel provisional reminder after server id replaces it`() {
        assertTrue(shouldCancelProvisionalReminder(localId = -42L, localScheduleStillExists = false))
    }

    @Test
    fun `keep provisional reminder while offline schedule still exists`() {
        assertFalse(shouldCancelProvisionalReminder(localId = -42L, localScheduleStillExists = true))
    }

    @Test
    fun `never treat persisted server id as provisional`() {
        assertFalse(shouldCancelProvisionalReminder(localId = 42L, localScheduleStillExists = false))
    }
}
