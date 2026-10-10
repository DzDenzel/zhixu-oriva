package com.planlock.app.record

import com.planlock.app.record.entity.PendingRecordEntity

/** Metadata on existing local_plan_change records; no new queue, schema or sync owner. */
object LocalPlanChangeIntent {
    const val PLAN_ONLY = "plan-only-v1"
    const val STRATEGY = "strategy-v1"
    fun payload(strategyChanged: Boolean) = if (strategyChanged) STRATEGY else PLAN_ONLY
    fun needsStrategyRetry(change: PendingRecordEntity, hasLocalStrategy: Boolean): Boolean =
        change.type == SnapshotProtection.LOCAL_PLAN_CHANGE && hasLocalStrategy &&
            (change.payload == STRATEGY || change.payload == null)
    fun mayClear(change: PendingRecordEntity, hasLocalStrategy: Boolean, strategyConfirmed: Boolean): Boolean =
        strategyConfirmed || !needsStrategyRetry(change, hasLocalStrategy)
}
