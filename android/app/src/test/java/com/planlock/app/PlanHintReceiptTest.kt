package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class PlanHintReceiptTest {
    @Test fun noConnectionOrOnlyZeroCannotProduceReleaseReceipt() {
        val state = PlanHintReceipt(); assertNull(state.beginRelease())
        state.connect(); state.observe(0); assertNull(state.beginRelease())
    }
    @Test fun activationAndNewZeroCallbackAreBothRequired() {
        val state = PlanHintReceipt(); state.connect(); state.observe(2)
        val ticket = requireNotNull(state.beginRelease())
        assertFalse(state.confirmed(ticket))
        state.observe(0); assertTrue(state.confirmed(ticket))
        state.observe(2); assertFalse(state.confirmed(ticket))
    }
    @Test fun reconnectAndNewRequestRejectOldAcknowledgement() {
        val state = PlanHintReceipt(); state.connect(); state.observe(2)
        val old = requireNotNull(state.beginRelease()); state.observe(0); assertTrue(state.confirmed(old))
        state.disconnect(); state.connect(); state.observe(0); assertFalse(state.confirmed(old))
        state.observe(2); val next = requireNotNull(state.beginRelease()); assertFalse(state.confirmed(next))
        state.observe(0); assertTrue(state.confirmed(next)); assertFalse(state.confirmed(old))
    }
}
