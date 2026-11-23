package com.planlock.app.record

import org.junit.Assert.*
import org.junit.Test

class PlanAlarmDeliveryLogCodecTest {

    @Test fun foregroundStartFailureRoundTripsAndCapsAtTwentyEntries() {
        var encoded = "[]"
        for (i in 1..25) {
            val failure = PlanForegroundStartFailure(
                planId = i.toLong(),
                planName = "计划-$i",
                scheduledAt = 1_000L * i,
                failedAt = 1_000L * i + 250L,
                deferred = i % 2 == 0,
                reason = "IllegalStateException: blocked-$i",
            )
            encoded = PlanForegroundStartFailureCodec.append(encoded, failure).second
        }
        val decoded = PlanForegroundStartFailureCodec.decode(encoded)
        assertEquals(20, decoded.size)
        assertEquals(6L, decoded.first().planId)
        assertEquals(25L, decoded.last().planId)
        assertEquals(false, decoded.last().deferred)
        assertEquals("IllegalStateException: blocked-25", decoded.last().reason)
    }

    @Test fun malformedJsonReturnsEmptyListWithoutThrowing() {
        assertTrue(PlanForegroundStartFailureCodec.decode("").isEmpty())
        assertTrue(PlanForegroundStartFailureCodec.decode("{not-json").isEmpty())
    }
}
