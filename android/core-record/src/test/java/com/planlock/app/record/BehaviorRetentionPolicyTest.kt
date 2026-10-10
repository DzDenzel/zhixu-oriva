package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorRetentionPolicyTest {
    @Test
    fun deletesExpiredRowsThenOldestOverflowRows() {
        val now = 100L * DAY
        val rows = listOf(
            row(1, now - 91L * DAY),
            row(2, now - 20L * DAY),
            row(3, now - 10L * DAY),
            row(4, now - 1L * DAY),
        )

        val ids = BehaviorRetentionPolicy(maxRows = 2, maxAgeMillis = 90L * DAY)
            .idsToDelete(rows, now)

        assertEquals(listOf(1L, 2L), ids)
    }

    @Test
    fun ignoresNonBehaviorQueueItemsAndKeepsAggregateInputIndependent() {
        val now = 100L * DAY
        val execution = PendingRecordEntity(id = 1, type = "execution", planId = 7, clientId = "e", createdAt = 0)
        val legacy = PendingRecordEntity(id = 2, type = "violation", planId = 7, clientId = "v", createdAt = 0)

        val ids = BehaviorRetentionPolicy(maxRows = 1, maxAgeMillis = 90L * DAY)
            .idsToDelete(listOf(execution, legacy), now)

        assertTrue(ids.isEmpty())
    }

    private fun row(id: Long, createdAt: Long) = PendingRecordEntity(
        id = id,
        type = "violation",
        planId = 7,
        startedAt = "2026-01-01T00:00:00",
        endedAt = "2026-01-01T00:01:00",
        durationMs = 60_000,
        clientId = "v-$id",
        createdAt = createdAt,
    )

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
