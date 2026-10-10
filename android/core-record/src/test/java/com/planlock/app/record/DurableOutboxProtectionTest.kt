package com.planlock.app.record
import com.planlock.app.record.entity.PendingRecordEntity
import org.junit.Test
import org.junit.Assert.*
class DurableOutboxProtectionTest {
    @Test fun failedPlanAndDeleteIntentsProtectRemotePull() {
        val pending = listOf("plan_upsert", "plan_delete", "strategy_upsert").mapIndexed { i, type -> PendingRecordEntity(type=type,planId=(i+1).toLong(),clientId="intent-$i") }
        assertEquals(setOf(1L,2L,3L), SnapshotProtection.from(pending).plans)
    }
}
