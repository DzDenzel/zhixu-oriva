package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity
import org.junit.Assert.*
import org.junit.Test

class SnapshotProtectionTest {
    @Test fun unsavedPlanAndDeletionProtectBothPlanAndStrategyIds() {
        val pending = listOf(
            PendingRecordEntity(type = SnapshotProtection.LOCAL_PLAN_CHANGE, planId = 8, clientId = "edit"),
            PendingRecordEntity(type = SnapshotProtection.LOCAL_PLAN_CHANGE, planId = 9, clientId = "delete"),
            PendingRecordEntity(type = "violation", planId = 10, clientId = "v"),
        )
        val protection = SnapshotProtection.from(pending)
        assertEquals(setOf(8L, 9L), protection.plans)
        assertFalse(10L in protection.plans)
    }
    @Test fun unuploadedScheduleEditAndDeleteCannotBeOverwrittenOrReinserted() {
        val pending = listOf(
            PendingRecordEntity(type = "schedule_upsert", planId = null, clientId = "s", payload = """{"localId":13,"baseVersion":2,"request":{"title":"local","startDate":"2026-10-02","endDate":"2026-10-02"}}"""),
            PendingRecordEntity(type = "schedule_delete", planId = null, clientId = "d", payload = "14"),
        )
        assertEquals(setOf(13L, 14L), SnapshotProtection.from(pending).schedules)
    }
    @Test fun completedQueueAllowsRemoteSnapshotsAgain() {
        assertEquals(SnapshotProtection(emptySet(), emptySet()), SnapshotProtection.from(emptyList()))
    }
}
