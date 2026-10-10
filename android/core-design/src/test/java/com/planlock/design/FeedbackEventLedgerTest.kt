package com.planlock.design

import org.junit.Assert.*
import org.junit.Test

class FeedbackEventLedgerTest {
    @Test fun sameEventIsConsumedOnceAcrossPageInstances() {
        val ledger = FeedbackEventLedger()
        assertTrue(ledger.consume("sync", "attempt-1"))
        assertFalse(ledger.consume("sync", "attempt-1"))
        assertTrue(ledger.consume("sync", "attempt-2"))
        assertFalse(ledger.consume("sync", "attempt-1"))
    }

    @Test fun independentSourcesDoNotSuppressEachOther() {
        val ledger = FeedbackEventLedger()
        assertTrue(ledger.consume("export", "1"))
        assertTrue(ledger.consume("plugin", "1"))
        assertFalse(ledger.consume("export", "1"))
    }

    @Test fun emptyIdentityIsNotAnEvent() {
        val ledger = FeedbackEventLedger()
        assertFalse(ledger.consume("sync", ""))
    }
}
