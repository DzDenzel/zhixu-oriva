package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class ListenerRebindGateTest {
    @Test fun unauthorizedOrConnectedNeverRequestsAndDoesNotConsumeOpportunity() {
        val gate = ListenerRebindGate()
        assertFalse(gate.shouldRequest(false, false, 0))
        assertFalse(gate.shouldRequest(true, true, 0))
        assertTrue(gate.shouldRequest(true, false, 0))
    }
    @Test fun repeatedEventsAreBoundedButLaterResumeCanRecover() {
        val gate = ListenerRebindGate()
        assertTrue(gate.shouldRequest(true, false, 100))
        assertFalse(gate.shouldRequest(true, false, 100))
        assertFalse(gate.shouldRequest(true, false, 10_099))
        assertTrue(gate.shouldRequest(true, false, 10_100))
    }
}
