package com.planlock.app.record

import com.planlock.app.record.dao.StrategySnapshotDao
import com.planlock.app.record.entity.StrategySnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrategySyncTest {
    @Test fun pullRetiresLegacyRestrictionFieldsAndKeepsCurrentSettings() = runBlocking {
        val dao = MemoryStrategies()
        dao.upsertAll(listOf(snapshot(1, 10).copy(blacklistApps = "legacy", strictMode = true)))
        dao.upsertRemote(listOf(snapshot(101, 10).copy(blacklistApps = "remote-legacy", autoDnd = false)))

        val current = dao.byPlanId(10)
        assertNull(current?.blacklistApps)
        assertNull(current?.strictMode)
        assertEquals(false, current?.autoDnd)
        assertEquals(1, dao.all().size)
    }

    private fun snapshot(id: Long, plan: Long) = StrategySnapshotEntity(id, plan, null, null, true, true, 1)

    private class MemoryStrategies : StrategySnapshotDao {
        private val rows = mutableMapOf<Long, StrategySnapshotEntity>()
        override suspend fun upsertAll(strategies: List<StrategySnapshotEntity>) { strategies.forEach { rows[it.id] = it } }
        override suspend fun all() = rows.values.toList()
        override suspend fun clear() = rows.clear()
        override suspend fun replacePlanId(oldPlanId: Long, newPlanId: Long) {
            rows.replaceAll { _, row -> if (row.planId == oldPlanId) row.copy(planId = newPlanId) else row }
        }
        override suspend fun updateVersion(planId: Long, version: Long) {
            rows.replaceAll { _, row -> if (row.planId == planId && (row.version ?: 0) <= version) row.copy(version = version) else row }
        }
        override suspend fun byPlanId(planId: Long) = rows.values.firstOrNull { it.planId == planId }
        override suspend fun deleteByPlanId(planId: Long) { rows.entries.removeAll { it.value.planId == planId } }
    }
}
