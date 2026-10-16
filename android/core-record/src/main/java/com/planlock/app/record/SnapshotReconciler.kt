package com.planlock.app.record

import androidx.room.withTransaction
import com.planlock.app.api.dto.*
import com.planlock.app.record.entity.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** All decisions and data changes share a SQLite transaction; no network calls occur here. */
internal class SnapshotReconciler(
    private val db: PlanLockDatabase,
    private val store: RecordStore,
    private val cancelReminder: (Long) -> Unit,
) {
    private val pending get() = db.pendingRecordDao()
    private val plans get() = db.planSnapshotDao()
    private val strategies get() = db.strategySnapshotDao()
    private val schedules get() = db.scheduleSnapshotDao()

    fun observeConfirmations() = pending.observeConfirmations().map { rows ->
        rows.filter { it.type == CONFLICT }.map { Json.decodeFromString<SyncConfirmation>(it.payload.orEmpty()) }
    }
    fun observeBulkDeletion() = pending.observeConfirmations().map { rows ->
        rows.firstOrNull { it.type == BULK }?.let { Json.decodeFromString<BulkDeletion>(it.payload.orEmpty()) }
    }
    suspend fun confirmations() = pending.all().filter { it.type == CONFLICT }.map { Json.decodeFromString<SyncConfirmation>(it.payload.orEmpty()) }
    suspend fun bulkDeletion() = pending.byClientId(BULK)?.let { Json.decodeFromString<BulkDeletion>(it.payload.orEmpty()) }

    private suspend fun metadata(type: String, key: String, payload: String) {
        val previous = pending.byClientId(key)
        if (previous == null) pending.insert(PendingRecordEntity(type = type, planId = null, clientId = key, payload = payload))
        else pending.update(previous.copy(payload = payload))
    }

    private suspend fun capture(kind: String, id: Long, remote: SyncPullResponse): SyncConfirmation {
        val intents = pending.all().filter { it.syncEntityKey() == "$kind:$id" }
        val last = intents.lastOrNull()
        val deleting = last?.type in setOf("plan_delete", "schedule_delete")
        val archive = last?.localSnapshot?.let { Json.decodeFromString<DeletedSnapshot>(it) }
        return SyncConfirmation(kind, id, deleting,
            plans.byId(id) ?: archive?.plan, strategies.byPlanId(id) ?: archive?.strategy,
            schedules.byId(id) ?: archive?.schedule,
            remote.plans?.firstOrNull { it.id == id }, remote.strategies?.firstOrNull { it.planId == id },
            remote.schedules?.firstOrNull { it.id == id }, intents.map { it.id },
            intents.any { it.type == "strategy_upsert" } || deleting && archive?.strategy != null)
    }

    suspend fun markConflict(row: PendingRecordEntity, remote: SyncPullResponse) = db.withTransaction {
        val key = row.syncEntityKey() ?: return@withTransaction
        val id = key.substringAfter(':').toLong()
        if (id <= 0 || pending.byId(row.id) == null) return@withTransaction
        val value = capture(key.substringBefore(':'), id, remote)
        metadata(CONFLICT, "conflict:$key", Json.encodeToString(value))
    }

    suspend fun reconcile(remotePlans: List<SyncPlanView>, remoteStrategies: List<SyncStrategyView>, remoteSchedules: List<SyncScheduleView>) = db.withTransaction {
        val remote = SyncPullResponse(remotePlans, remoteSchedules, remoteStrategies)
        val planIds = remotePlans.map { it.id }.toSet()
        val scheduleIds = remoteSchedules.map { it.id }.toSet()
        // A complete pull is also the acknowledgement for a deletion whose response was lost.
        pending.all().filter { it.type in setOf("plan_delete", "schedule_delete") }.forEach { row ->
            val key = requireNotNull(row.syncEntityKey())
            val id = key.substringAfter(':').toLong()
            if (id > 0 && id !in (if (row.type == "plan_delete") planIds else scheduleIds)) {
                // The deletion acknowledges only the lineage before it, never a later editor save.
                pending.delete(pending.all().filter { it.syncEntityKey() == key && it.id <= row.id }.map { it.id })
                pending.deleteByClientId("conflict:$key")
            }
        }
        pending.all().filter { it.type in setOf("plan_upsert", "strategy_upsert", "schedule_upsert") }.forEach { row ->
            val key = requireNotNull(row.syncEntityKey())
            val id = key.substringAfter(':').toLong()
            if (id > 0 && id !in (if (key.startsWith("plan:")) planIds else scheduleIds)) markConflict(row, remote)
        }
        // Refresh the comparison atomically, including edits made while a dialog was open.
        confirmations().forEach { old ->
            metadata(CONFLICT, "conflict:${old.key}", Json.encodeToString(capture(old.kind, old.id, remote)))
        }
        val rows = pending.all()
        val protection = SnapshotProtection.from(rows)
        val localPlans = plans.all()
        val localSchedules = schedules.all()
        val missing = BulkDeletion(
            localPlans.filter { it.id > 0 && it.id !in planIds && it.id !in protection.plans }.map { it.id }.sorted(),
            localSchedules.filter { it.id > 0 && it.id !in scheduleIds && it.id !in protection.schedules }.map { it.id }.sorted(),
        )
        val confirmedTotal = localPlans.count { it.id > 0 && it.id !in protection.plans } + localSchedules.count { it.id > 0 && it.id !in protection.schedules }
        val previous = bulkDeletion()
        val approved = previous?.approved == true && previous.copy(approved = false) == missing
        val deferredPlans = if (missing.needsConfirmation(confirmedTotal) && !approved) missing.plans.toSet() else emptySet()
        if (missing.needsConfirmation(confirmedTotal) && !approved) {
            metadata(BULK, BULK, Json.encodeToString(missing))
        } else {
            missing.plans.forEach { removePlan(it) }
            missing.schedules.forEach { removeSchedule(it) }
            pending.deleteByClientId(BULK)
        }
        val planKeys = rows.filter { it.type == "plan_upsert" }.mapNotNull { Json.decodeFromString<SyncPlanItem>(it.payload.orEmpty()).clientId }.toSet()
        val scheduleKeys = rows.filter { it.type == "schedule_upsert" }.mapNotNull { Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).request.clientId }.toSet()
        val protectedRemotePlans = remotePlans.filter { it.clientId != null && it.clientId in planKeys }.map { it.id }.toSet()
        plans.upsertAll(remotePlans.filterNot { it.id in protection.plans || it.id in protectedRemotePlans }.map { it.toSnapshot() })
        val remoteStrategyPlans = remoteStrategies.map { it.planId }.toSet()
        strategies.all().filter { it.planId > 0 && it.planId !in protection.plans && it.planId !in protectedRemotePlans && it.planId !in deferredPlans && it.planId !in remoteStrategyPlans }.forEach { strategies.deleteByPlanId(it.planId) }
        strategies.upsertRemote(remoteStrategies.filterNot { it.planId in protection.plans || it.planId in protectedRemotePlans }.map { it.toSnapshot() })
        schedules.upsertAll(remoteSchedules.filterNot { it.id in protection.schedules || it.clientId != null && it.clientId in scheduleKeys }.map { it.toSnapshot() })
    }

    private suspend fun removePlan(id: Long) {
        store.rememberPlanDeleted(id)
        plans.delete(id)
        strategies.deleteByPlanId(id)
        schedules.markPlanDeleted(id)
        pending.all().filter { it.type == "schedule_upsert" }.forEach { row ->
            val item = Json.decodeFromString<SchedulePendingUpsert>(row.payload.orEmpty())
            if (item.request.linkedPlanId == id) pending.update(row.copy(payload = Json.encodeToString(item.copy(request = item.request.copy(linkedPlanId = null)))))
        }
    }
    private suspend fun removeSchedule(id: Long) {
        schedules.delete(id)
        cancelReminder(id)
    }

    suspend fun approveBulkDeletion(expected: BulkDeletion) = db.withTransaction {
        check(bulkDeletion() == expected) { "待清理的记录已变化，请重新确认" }
        metadata(BULK, BULK, Json.encodeToString(expected.copy(approved = true)))
    }

    suspend fun resolve(expected: SyncConfirmation, keepMine: Boolean) {
        val resolved = db.withTransaction {
            val row = pending.byClientId("conflict:${expected.key}") ?: return@withTransaction false
            val old = Json.decodeFromString<SyncConfirmation>(row.payload.orEmpty())
            val remote = SyncPullResponse(listOfNotNull(old.remotePlan), listOfNotNull(old.remoteSchedule), listOfNotNull(old.remoteStrategy))
            val current = capture(old.kind, old.id, remote)
            if (old != expected || current != old) {
                metadata(CONFLICT, row.clientId, Json.encodeToString(current))
                return@withTransaction false
            }
            pending.delete(old.intentIds)
            pending.deleteByClientId(row.clientId)
            pending.deleteByClientId("local-confirmation:${old.kind}:${old.id}")
            if (old.kind == "plan") pending.deleteByClientId("local-confirmation:strategy:${old.id}")
            if (!keepMine) {
                if (old.kind == "plan") {
                    if (old.remoteDeleted) removePlan(old.id)
                    else { plans.delete(old.id); strategies.deleteByPlanId(old.id) }
                    old.remotePlan?.let { plans.upsertAll(listOf(it.toSnapshot())) }
                    old.remoteStrategy?.let { strategies.upsertRemote(listOf(it.toSnapshot())) }
                } else {
                    removeSchedule(old.id)
                    old.remoteSchedule?.let { schedules.upsertAll(listOf(it.toSnapshot())) }
                }
            } else if (old.deleting) {
                if (!old.remoteDeleted) {
                    if (old.kind == "plan") {
                        plans.upsertAll(listOf((old.localPlan ?: requireNotNull(old.remotePlan).toSnapshot()).copy(version = old.remotePlan?.version)))
                        old.localStrategy?.let { strategies.upsertAll(listOf(it)) }
                        store.deletePlan(old.id)
                    } else {
                        schedules.upsertAll(listOf((old.localSchedule ?: requireNotNull(old.remoteSchedule).toSnapshot()).copy(version = requireNotNull(old.remoteSchedule).version)))
                        store.enqueueScheduleDelete(old.id)
                    }
                }
            } else if (old.kind == "plan") {
                val local = requireNotNull(old.localPlan) { "本机版本缺失，已保留确认状态" }
                val id = if (old.remoteDeleted) newLocalId() else old.id
                plans.delete(old.id)
                strategies.deleteByPlanId(old.id)
                // A deleted plan restored as a new creation retains local linked schedules.
                if (id != old.id) {
                    store.moveRestoredPlanRecords(old.id, id)
                    schedules.replaceLinkedPlanId(old.id, id)
                    pending.all().filter { it.type == "schedule_upsert" }.forEach { queued ->
                        val item = Json.decodeFromString<SchedulePendingUpsert>(queued.payload.orEmpty())
                        if (item.request.linkedPlanId == old.id) pending.update(queued.copy(payload = Json.encodeToString(item.copy(request = item.request.copy(linkedPlanId = id)))))
                    }
                }
                // The choice is the complete displayed version, including execution settings.
                val strategy = old.localStrategy?.copy(planId = id, version = old.remoteStrategy?.version)
                    ?: old.remoteStrategy?.let { StrategySnapshotEntity(it.id,id,null,null,false,false,it.version) }
                store.updateLocalPlan(local.copy(id = id, version = old.remotePlan?.version), strategy = strategy)
            } else {
                val local = requireNotNull(old.localSchedule) { "本机版本缺失，已保留确认状态" }
                val id = if (old.remoteDeleted) newLocalId() else old.id
                if (id != old.id) store.rememberRestoredSchedule(old.id, id)
                schedules.delete(old.id)
                cancelReminder(old.id)
                val restored = local.copy(id = id, version = old.remoteSchedule?.version ?: 0)
                store.saveScheduleChange(restored, restored.toRequest())
            }
            true
        }
        check(resolved) { "内容已更新，请重新查看两边的版本后确认" }
    }

    companion object {
        const val CONFLICT = "sync_conflict"
        const val BULK = "bulk_delete"
        private fun newLocalId() = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
    }
}

internal fun SyncPlanView.toSnapshot() = PlanSnapshotEntity(id,userId,name,type,startTime,endTime,repeatRule,color,icon,remindAdvanceMin,remindMethods,status,version)
internal fun SyncStrategyView.toSnapshot() = StrategySnapshotEntity(id,planId,null,whitelistApps,autoDnd,notifySilence,version)
internal fun SyncScheduleView.toSnapshot() = ScheduleSnapshotEntity(id,userId,title,startDate,startTime,endDate,endTime,allDay,location,note,remindAdvanceMin,repeatRule,linkedPlanId,false,tzId,status,version)
internal fun ScheduleSnapshotEntity.toRequest() = ScheduleUpsertRequest(title,startDate,startTime,endDate,endTime,allDay,location,note,remindAdvanceMin,repeatRule,linkedPlanId,tzId)
