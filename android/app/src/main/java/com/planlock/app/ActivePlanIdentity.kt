package com.planlock.app

import com.planlock.app.record.entity.PlanSnapshotEntity

internal data class ActivePlanIdentity(val previousId: Long?, val cachedPlan: PlanSnapshotEntity?, val activeId: Long?)

/** Resolve one logical raw identity once, so mapping between reads cannot invent an edge. */
internal suspend fun activePlanIdentity(
    previousId: Long?,
    cachedPlan: PlanSnapshotEntity?,
    resolve: suspend (Long) -> Long,
    activeSnapshotId: Long? = null,
): ActivePlanIdentity {
    val previous = previousId?.let { resolve(it) }
    suspend fun canonical(id: Long) = if (id == previousId) requireNotNull(previous) else resolve(id)
    return ActivePlanIdentity(previous, cachedPlan?.let { it.copy(id = canonical(it.id)) }, activeSnapshotId?.let { canonical(it) })
}
