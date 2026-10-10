package com.planlock.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DndFeedbackGateTest {
    @Test
    fun samePlanIsNotNotifiedAgainBeforeTheInterval() {
        val gate = DndFeedbackGate(intervalMillis = 60 * 60 * 1000L)

        assertTrue(gate.shouldEmit(planId = 7, nowMillis = 1_000L))
        assertFalse(gate.shouldEmit(planId = 7, nowMillis = 1_000L + 59 * 60 * 1000L))
    }

    @Test
    fun aDifferentPlanCanNotifyImmediately() {
        val gate = DndFeedbackGate(intervalMillis = 60 * 60 * 1000L)

        gate.shouldEmit(planId = 7, nowMillis = 1_000L)

        assertTrue(gate.shouldEmit(planId = 8, nowMillis = 2_000L))
    }

    @Test
    fun samePlanCanNotifyAgainAfterTheInterval() {
        val gate = DndFeedbackGate(intervalMillis = 60 * 60 * 1000L)

        gate.shouldEmit(planId = 7, nowMillis = 1_000L)

        assertTrue(gate.shouldEmit(planId = 7, nowMillis = 1_000L + 60 * 60 * 1000L))
    }
}
