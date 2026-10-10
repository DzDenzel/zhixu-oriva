package com.planlock.feature.plan

import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.OccurrenceDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanActionGateTest {
    private val active = PlanSnapshotEntity(1, 1, "计划", "学习", "09:00:00", "11:00:00", null, null, null, 15, null, 1, 0)
    private fun date(iso: Int) = OccurrenceDate(2026, 10, 4 + iso, iso)

    @Test
    fun `active pause delete and edit require password`() {
        assertTrue(PlanActionGate.requiresPassword(active, ProtectedPlanAction.PAUSE, 600, date(1), true))
        assertTrue(PlanActionGate.requiresPassword(active, ProtectedPlanAction.DELETE, 600, date(1), true))
        assertTrue(PlanActionGate.requiresPassword(active, ProtectedPlanAction.EDIT, 600, date(1), true))
    }

    @Test
    fun `resume and actions outside window are free`() {
        assertFalse(PlanActionGate.requiresPassword(active.copy(status = 0), ProtectedPlanAction.PAUSE, 600, date(1), true))
        assertFalse(PlanActionGate.requiresPassword(active, ProtectedPlanAction.DELETE, 720, date(1), true))
        assertFalse(PlanActionGate.requiresPassword(active, ProtectedPlanAction.EDIT, 720, date(1), true))
    }

    @Test
    fun `non occurrence day does not trigger protected action`() {
        val mondayOnly = active.copy(repeatRule = "{\"type\":\"weekly\",\"weekdays\":[1]}")
        assertFalse(PlanActionGate.requiresPassword(mondayOnly, ProtectedPlanAction.PAUSE, 600, date(2), true))
        assertTrue(PlanActionGate.requiresPassword(mondayOnly, ProtectedPlanAction.PAUSE, 600, date(1), true))
    }

    @Test fun `password gate is optional and off by default`() {
        assertFalse(PlanActionGate.requiresPassword(active, ProtectedPlanAction.DELETE, 600, date(1), false))
    }
}
