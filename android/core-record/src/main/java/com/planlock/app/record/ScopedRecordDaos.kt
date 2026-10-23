package com.planlock.app.record

import com.planlock.app.record.dao.*
import com.planlock.app.record.entity.*

/** Repository adapters bind the mandatory Room scope; no unscoped SQL is exposed. */
internal class ScopedPlans(private val dao: PlanSnapshotDao, private val scope: () -> String) {
    suspend fun all() = dao.all(scope())
    suspend fun byId(id: Long) = dao.byId(scope(), id)
    suspend fun delete(id: Long) = dao.delete(scope(), id)
    suspend fun replaceId(old: Long, new: Long) = dao.replaceId(scope(), old, new)
    suspend fun updateVersion(id: Long, version: Long) = dao.updateVersion(scope(), id, version)
    suspend fun upsertAll(rows: List<PlanSnapshotEntity>) = dao.upsertAll(rows.map { it.copy(scope = scope(), userId = scopeOwner(scope())) })
}
internal class ScopedStrategies(private val dao: StrategySnapshotDao, private val scope: () -> String) {
    suspend fun all() = dao.all(scope())
    suspend fun byPlanId(id: Long) = dao.byPlanId(scope(), id)
    suspend fun deleteByPlanId(id: Long) = dao.deleteByPlanId(scope(), id)
    suspend fun replacePlanId(old: Long, new: Long) = dao.replacePlanId(scope(), old, new)
    suspend fun updateVersion(id: Long, version: Long) = dao.updateVersion(scope(), id, version)
    suspend fun upsertAll(rows: List<StrategySnapshotEntity>) = dao.upsertAll(rows.map { it.copy(scope = scope()) })
    suspend fun upsertRemote(rows: List<StrategySnapshotEntity>) = dao.upsertRemote(rows.map { it.copy(scope = scope()) })
}
internal class ScopedSchedules(private val dao: ScheduleSnapshotDao, private val scope: () -> String) {
    suspend fun all() = dao.all(scope())
    suspend fun byId(id: Long) = dao.byId(scope(), id)
    suspend fun delete(id: Long) = dao.delete(scope(), id)
    suspend fun replaceId(old: Long, new: Long) = dao.replaceId(scope(), old, new)
    suspend fun replaceLinkedPlanId(old: Long, new: Long) = dao.replaceLinkedPlanId(scope(), old, new)
    suspend fun markPlanDeleted(id: Long) = dao.markPlanDeleted(scope(), id)
    suspend fun upsertAll(rows: List<ScheduleSnapshotEntity>) = dao.upsertAll(rows.map { it.copy(scope = scope(), userId = scopeOwner(scope())) })
}
internal class ScopedPending(private val dao: PendingRecordDao, private val scope: () -> String) {
    suspend fun all() = dao.all(scope())
    suspend fun byId(id: Long) = dao.byId(scope(), id)
    suspend fun byClientId(id: String) = dao.byClientId(scope(), id)
    suspend fun countByClientId(id: String) = dao.countByClientId(scope(), id)
    suspend fun delete(ids: List<Long>) = dao.delete(scope(), ids)
    suspend fun deleteByClientId(id: String) = dao.deleteByClientId(scope(), id)
    suspend fun replaceProtectedPlanId(old: Long, new: Long) = dao.replaceProtectedPlanId(scope(), old, new)
    suspend fun behaviorDetails() = dao.behaviorDetails(scope())
    fun observeConfirmations() = dao.observeConfirmations(scope())
    suspend fun insert(row: PendingRecordEntity) = dao.insert(row.copy(scope = scope()))
    suspend fun update(row: PendingRecordEntity) {
        check(dao.byId(scope(), row.id) != null) { "记录归属已变化" }
        dao.update(row.copy(scope = scope()))
    }
}
internal class ScopedAggregates(private val dao: BehaviorAggregateDao, private val scope: () -> String) {
    suspend fun all() = dao.all(scope())
    suspend fun deleteByPlanId(id: Long) = dao.deleteByPlanId(scope(), id)
    suspend fun upsertAll(rows: List<BehaviorAggregateEntity>) = dao.upsertAll(rows.map { it.copy(scope = scope()) })
    suspend fun add(planId: Long, packageName: String, sourceLevel: String, durationMs: Long, endedAt: String?) =
        dao.add(scope(), planId, packageName, sourceLevel, durationMs, endedAt)
}

internal fun scopeOwner(scope: String): Long = scope.substringAfterLast('\n').toLongOrNull() ?: 0L
