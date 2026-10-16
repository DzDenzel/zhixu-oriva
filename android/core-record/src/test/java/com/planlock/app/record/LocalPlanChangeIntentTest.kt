package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity
import org.junit.Assert.*
import org.junit.Test

class LocalPlanChangeIntentTest {
    private fun change(payload: String?)=PendingRecordEntity(type=SnapshotProtection.LOCAL_PLAN_CHANGE,planId=5,clientId="test",payload=payload)
    @Test fun failedExplicitStrategyCommitStillRequiresRetryAfterReopening() {
        val pending=change(LocalPlanChangeIntent.payload(true))
        assertTrue(LocalPlanChangeIntent.needsStrategyRetry(pending,true))
        assertFalse(LocalPlanChangeIntent.mayClear(pending,true,false))
        assertTrue(LocalPlanChangeIntent.mayClear(pending,true,true))
    }
    @Test fun nameOnlyFailureNeverCreatesOrUploadsStrategy() {
        val pending=change(LocalPlanChangeIntent.payload(false))
        assertFalse(LocalPlanChangeIntent.needsStrategyRetry(pending,false))
        assertFalse(LocalPlanChangeIntent.needsStrategyRetry(pending,true))
        assertTrue(LocalPlanChangeIntent.mayClear(pending,true,false))
    }
    @Test fun legacyUnclassifiedMarkerRetainsExistingStrategyUntilConfirmed() {
        val legacy=change(null)
        assertTrue(LocalPlanChangeIntent.needsStrategyRetry(legacy,true))
        assertFalse(LocalPlanChangeIntent.mayClear(legacy,true,false))
        assertFalse(LocalPlanChangeIntent.needsStrategyRetry(legacy,false))
        assertTrue(LocalPlanChangeIntent.mayClear(legacy,false,false))
    }
    @Test fun laterPlanOnlyConfirmationCannotReleaseEarlierStrategyIntent() {
        val pending=listOf(change(LocalPlanChangeIntent.STRATEGY),change(LocalPlanChangeIntent.PLAN_ONLY))
        val remaining=pending.filterNot { LocalPlanChangeIntent.mayClear(it,true,false) }
        assertEquals(listOf(pending.first()),remaining)
        assertTrue(SnapshotProtection.from(remaining).plans.contains(5L))
    }
}
