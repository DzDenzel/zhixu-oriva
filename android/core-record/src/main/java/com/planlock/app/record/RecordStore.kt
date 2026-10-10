package com.planlock.app.record

import android.content.Context
import androidx.room.withTransaction
import com.planlock.app.api.dto.*
import kotlinx.serialization.decodeFromString
import com.planlock.app.api.dto.ExecutionRecordRequest
import com.planlock.app.api.dto.SyncPlanView
import com.planlock.app.api.dto.SyncStrategyView
import com.planlock.app.api.dto.SyncScheduleView
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.api.dto.UnlockRecordRequest
import com.planlock.app.api.dto.ViolationRecordRequest
import com.planlock.app.record.entity.PendingRecordEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 本地记录采集 + 计划/策略快照缓存 + 离线补传队列（Room）。
 */
class RecordStore(private val database: PlanLockDatabase) {
    constructor(context: Context) : this(PlanLockDatabase.get(context))
    private val planDao = database.planSnapshotDao()
    private val strategyDao = database.strategySnapshotDao()
    private val pendingDao = database.pendingRecordDao()
    private val scheduleDao = database.scheduleSnapshotDao()
    private val aggregateDao = database.behaviorAggregateDao()
    private val retention = BehaviorRetentionPolicy()

    suspend fun saveSnapshots(plans: List<SyncPlanView>, strategies: List<SyncStrategyView>, schedules: List<SyncScheduleView> = emptyList()) = database.withTransaction {
        val pending = pendingDao.all()
        val protection = SnapshotProtection.from(pending)
        val planKeys = pending.filter { it.type == "plan_upsert" }.mapNotNull { Json.decodeFromString<SyncPlanItem>(it.payload.orEmpty()).clientId }.toSet()
        val scheduleKeys = pending.filter { it.type == "schedule_upsert" }.mapNotNull { Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).request.clientId }.toSet()
        planDao.upsertAll(plans.filterNot { it.id in protection.plans || it.clientId != null && it.clientId in planKeys }.map {
            PlanSnapshotEntity(
                id = it.id,
                userId = it.userId,
                name = it.name,
                type = it.type,
                startTime = it.startTime,
                endTime = it.endTime,
                repeatRule = it.repeatRule,
                color = it.color,
                icon = it.icon,
                remindAdvanceMin = it.remindAdvanceMin,
                remindMethods = it.remindMethods,
                status = it.status,
                version = it.version
            )
        })
        val protectedRemotePlanIds = plans.filter { it.clientId != null && it.clientId in planKeys }.map { it.id }.toSet()
        strategyDao.upsertRemote(strategies.filterNot { it.planId in protection.plans || it.planId in protectedRemotePlanIds }.map {
            StrategySnapshotEntity(
                id = it.id,
                planId = it.planId,
                blacklistApps = null,
                whitelistApps = it.whitelistApps,
                autoDnd = it.autoDnd,
                notifySilence = it.notifySilence,
                version = it.version
            )
        })
        scheduleDao.upsertAll(schedules.filterNot { it.id in protection.schedules || it.clientId != null && it.clientId in scheduleKeys }.map { it.toEntity() })
    }

    suspend fun enqueueExecution(req: ExecutionRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "execution",
                planId = req.planId,
                planDate = req.planDate,
                status = req.status,
                startedAt = req.startedAt,
                endedAt = req.endedAt,
                clientId = req.clientId
            )
        )
    }

    suspend fun enqueueViolation(req: ViolationRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "violation",
                planId = req.planId,
                recordAt = req.recordAt,
                appName = req.appName,
                packageName = req.packageName,
                startedAt = req.startedAt,
                endedAt = req.endedAt,
                durationMs = req.durationMs,
                sourceLevel = req.sourceLevel,
                clientId = req.clientId
            )
        )
    }

    suspend fun enqueueUnlock(req: UnlockRecordRequest) {
        insertPending(
            PendingRecordEntity(
                type = "unlock",
                planId = req.planId,
                unlockAt = req.unlockAt,
                method = req.method,
                clientId = req.clientId
            )
        )
    }

    suspend fun pending(): List<PendingRecordEntity> = pendingDao.all().filterNot { it.type in METADATA_TYPES }

    suspend fun pendingById(id: Long): PendingRecordEntity? = pendingDao.byId(id)?.takeUnless { it.type in METADATA_TYPES }

    private suspend fun resolveId(id: Long, type: String): Long {
        if (id > 0) return id
        val aliases = pendingDao.all().filter { it.type == type && it.payload == id.toString() }
        check(aliases.mapNotNull { it.planId }.distinct().size <= 1) { "Conflicting identity mapping" }
        return aliases.firstOrNull()?.planId?.also { require(it > 0) { "Identity mapping must be canonical" } } ?: id
    }
    suspend fun resolvePlanId(id: Long): Long = resolveId(id, "plan_identity")
    suspend fun resolveScheduleId(id: Long): Long = resolveId(id, "schedule_identity")

    private fun confirmationKey(kind: String, id: Long) = "local-confirmation:$kind:$id"
    private suspend fun confirmationRow(kind: String, id: Long) = pendingDao.byClientId(confirmationKey(kind, id))?.also {
        check(it.type == "local_confirmation") { "Conflicting local confirmation" }
    }
    private suspend fun confirmation(kind: String, id: Long) = confirmationRow(kind, id)?.let {
        Json.decodeFromString<LocalConfirmation>(it.payload.orEmpty()).also { state -> check(state.kind == kind) }
    }
    private suspend fun saveConfirmation(kind: String, id: Long, state: LocalConfirmation) {
        val row = confirmationRow(kind, id)
        val payload = Json.encodeToString(state)
        if (row == null) pendingDao.insert(PendingRecordEntity(type = "local_confirmation", planId = id, clientId = confirmationKey(kind, id), payload = payload))
        else pendingDao.update(row.copy(payload = payload))
    }

    /** Only consecutive confirmed local requests prove a version lineage; pulls never extend it. */
    private suspend fun recordAcknowledgement(kind: String, id: Long, base: Long?, version: Long) {
        val old = confirmation(kind, id)
        val next = if (base != null && base >= 0 && version == base + 1) {
            if (old != null && old.toVersion == base) old.copy(toVersion = version)
            else LocalConfirmation(kind, base, version, deleted = old?.deleted == true)
        } else LocalConfirmation(kind, version, version,
            acceptsNullBase = kind == "strategy" && base == null && version == 0L,
            deleted = old?.deleted == true)
        saveConfirmation(kind, id, next)
    }
    private suspend fun rebasedVersion(kind: String, id: Long, captured: Long?, current: Long?): Long? {
        if (id <= 0) return captured
        val known = confirmation(kind, id) ?: return captured
        val covered = captured?.let { it in known.fromVersion..known.toVersion } ?: known.acceptsNullBase
        return if (!known.deleted && covered && current == known.toVersion) known.toVersion else captured
    }
    private suspend fun normalizedLinkedPlan(id: Long?): Long? {
        val canonical = id?.let { resolvePlanId(it) } ?: return null
        return canonical.takeUnless { confirmation("plan", it)?.deleted == true }
    }

    suspend fun behaviorAggregates() = aggregateDao.all()

    suspend fun removePending(ids: List<Long>) = pendingDao.delete(ids)

    private suspend fun insertPending(record: PendingRecordEntity) = pendingMutex.withLock {
        if (pendingDao.countByClientId(record.clientId) != 0) return@withLock
        database.withTransaction {
            // Resolve under the same Room transaction as insertion and aggregation.
            val canonical = record.copy(planId = record.planId?.let { resolvePlanId(it) })
            pendingDao.insert(canonical)
            val duration = canonical.durationMs
            val packageName = canonical.packageName
            if (canonical.type == "violation" && duration != null && packageName != null) {
                aggregateDao.add(
                    planId = requireNotNull(canonical.planId),
                    packageName = packageName,
                    sourceLevel = canonical.sourceLevel ?: ObservationSource.ACCESSIBILITY.name,
                    durationMs = duration,
                    endedAt = canonical.endedAt,
                )
                val expiredIds = retention.idsToDelete(pendingDao.behaviorDetails(), System.currentTimeMillis())
                if (expiredIds.isNotEmpty()) pendingDao.delete(expiredIds)
            }
        }
    }

    // ---- 本地为源闭环（BUG-005）：计划/策略先落 Room，网络可用时再同步 ----

    suspend fun saveLocalPlan(plan: PlanSnapshotEntity) = planDao.upsertAll(listOf(plan))

    suspend fun saveLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun plans(): List<PlanSnapshotEntity> = planDao.all()

    suspend fun plan(id: Long): PlanSnapshotEntity? = planDao.byId(resolvePlanId(id))

    suspend fun strategy(planId: Long): StrategySnapshotEntity? = strategyDao.byPlanId(resolvePlanId(planId))

    /** Commit the visible snapshot and immutable transmission intent in one Room transaction. */
    suspend fun updateLocalPlan(input: PlanSnapshotEntity, strategyChanged: Boolean = false, strategy: StrategySnapshotEntity? = null): String = database.withTransaction {
        val canonicalId = resolvePlanId(input.id)
        val plan = input.copy(id = canonicalId, version = if (canonicalId != input.id) planDao.byId(canonicalId)?.version ?: input.version else rebasedVersion("plan", canonicalId, input.version, planDao.byId(canonicalId)?.version))
        val marker = "plan-${UUID.randomUUID()}"
        val previous = pendingDao.all().firstOrNull { it.type == "plan_upsert" && it.planId == plan.id }
        val identity = previous?.let { Json.decodeFromString<SyncPlanItem>(it.payload.orEmpty()).clientId } ?: marker
        val item = plan.toSyncItem(identity)
        pendingDao.insert(PendingRecordEntity(type = "plan_upsert", planId = plan.id, clientId = marker, payload = Json.encodeToString(item)))
        planDao.upsertAll(listOf(plan))
        val changed = strategy?.copy(planId = canonicalId, version = if (canonicalId != strategy.planId) strategyDao.byPlanId(canonicalId)?.version ?: strategy.version else rebasedVersion("strategy", canonicalId, strategy.version, strategyDao.byPlanId(canonicalId)?.version)) ?: if (strategyChanged) strategyDao.byPlanId(plan.id) else null
        if (changed != null) {
            strategyDao.upsertAll(listOf(changed))
            pendingDao.insert(PendingRecordEntity(type = "strategy_upsert", planId = plan.id, clientId = "strategy-${UUID.randomUUID()}", payload = Json.encodeToString(changed.toSyncItem())))
        }
        marker
    }

    /** A previous explicit strategy edit must be retried even after reopening the editor. */
    suspend fun hasPendingStrategyChange(planId: Long): Boolean = database.withTransaction {
        val hasStrategy = strategyDao.byPlanId(planId) != null
        pendingDao.all().any { it.planId == planId && (it.type == "strategy_upsert" || LocalPlanChangeIntent.needsStrategyRetry(it, hasStrategy)) }
    }

    /** Clear acknowledged changes only; later edits and unacknowledged strategies stay protected. */
    suspend fun confirmLocalPlanChange(marker: String, strategyConfirmed: Boolean = false) = database.withTransaction {
        val changes = pendingDao.all().filter { it.type == SnapshotProtection.LOCAL_PLAN_CHANGE }
        val confirmed = changes.firstOrNull { it.clientId == marker } ?: return@withTransaction
        val hasStrategy = confirmed.planId?.let { strategyDao.byPlanId(it) } != null
        pendingDao.delete(changes.filter {
            it.planId == confirmed.planId && it.id <= confirmed.id &&
                LocalPlanChangeIntent.mayClear(it, hasStrategy, strategyConfirmed)
        }.map { it.id })
    }

    suspend fun saveConfirmedVersions(id: Long, planVersion: Long?, strategyVersion: Long?) = database.withTransaction {
        planVersion?.let { planDao.updateVersion(id, it) }
        strategyVersion?.let { strategyDao.updateVersion(id, it) }
    }

    suspend fun updateLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun deletePlan(inputId: Long): String = database.withTransaction {
        val id = resolvePlanId(inputId)
        val marker = "plan-delete-${UUID.randomUUID()}"
        pendingDao.insert(PendingRecordEntity(type = "plan_delete", planId = id, clientId = marker))
        val previous = confirmation("plan", id) ?: LocalConfirmation("plan", 0, 0)
        saveConfirmation("plan", id, previous.copy(deleted = true))
        scheduleDao.markPlanDeleted(id)
        pendingDao.all().filter { it.type == "schedule_upsert" }.forEach { row ->
            val queued = Json.decodeFromString<SchedulePendingUpsert>(row.payload.orEmpty())
            if (queued.request.linkedPlanId?.let { resolvePlanId(it) } == id) {
                pendingDao.update(row.copy(payload = Json.encodeToString(queued.copy(request = queued.request.copy(linkedPlanId = null)))))
            }
        }
        planDao.delete(id)
        strategyDao.deleteByPlanId(id)
        marker
    }

    /** All references move together, including records created before the create response arrived. */
    suspend fun replacePlanId(localId: Long, serverId: Long) = database.withTransaction { mapPlanId(localId, serverId) }

    private suspend fun mapPlanId(localId: Long, serverId: Long) {
        require(serverId > 0)
        if (localId == serverId) return
        require(localId < 0 && resolvePlanId(localId) in setOf(localId, serverId)) { "Conflicting plan identity" }
        if (pendingDao.countByClientId("plan-identity-$localId") == 0) pendingDao.insert(PendingRecordEntity(type = "plan_identity", planId = serverId, clientId = "plan-identity-$localId", payload = localId.toString()))
        planDao.delete(serverId)
        planDao.replaceId(localId, serverId)
        strategyDao.deleteByPlanId(serverId)
        strategyDao.replacePlanId(localId, serverId)
        scheduleDao.replaceLinkedPlanId(localId, serverId)
        val aggregates = aggregateDao.all()
        aggregates.filter { it.planId == localId }.forEach { old ->
            val existing = aggregates.firstOrNull { it.planId == serverId && it.packageName == old.packageName && it.sourceLevel == old.sourceLevel }
            aggregateDao.upsertAll(listOf(old.copy(planId = serverId, totalDurationMs = old.totalDurationMs + (existing?.totalDurationMs ?: 0), occurrenceCount = old.occurrenceCount + (existing?.occurrenceCount ?: 0), lastEndedAt = listOfNotNull(old.lastEndedAt, existing?.lastEndedAt).maxOrNull())))
        }
        aggregateDao.deleteByPlanId(localId)
        pendingDao.all().filter { it.type == "local_confirmation" && it.planId == localId }.forEach { row ->
            val kind = Json.decodeFromString<LocalConfirmation>(row.payload.orEmpty()).kind
            if (kind in setOf("plan", "strategy")) {
                val key = confirmationKey(kind, serverId)
                check(pendingDao.byClientId(key) == null) { "Conflicting mapped local confirmation" }
                pendingDao.update(row.copy(clientId = key))
            }
        }
        pendingDao.replaceProtectedPlanId(localId, serverId)
        pendingDao.all().forEach { p ->
            val payload = when (p.type) {
                "plan_upsert" -> Json.encodeToString(Json.decodeFromString<SyncPlanItem>(p.payload.orEmpty()).copy(id = serverId))
                "strategy_upsert" -> Json.decodeFromString<SyncStrategyItem>(p.payload.orEmpty()).let { if (it.planId == localId) Json.encodeToString(it.copy(planId = serverId)) else p.payload }
                "schedule_upsert" -> Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty()).let { if (it.request.linkedPlanId == localId) Json.encodeToString(it.copy(request = it.request.copy(linkedPlanId = serverId))) else p.payload }
                else -> p.payload
            }
            if ((p.type != "plan_upsert" || p.planId == serverId) && payload != p.payload) pendingDao.update(p.copy(payload = payload))
        }
    }

    /** Acknowledgement removes only the transmitted row; later edits retain their own snapshot. */
    suspend fun acknowledgePlan(sent: PendingRecordEntity, serverId: Long, version: Long) = database.withTransaction {
        val localId = requireNotNull(sent.planId)
        if (localId < 0) mapPlanId(localId, serverId)
        recordAcknowledgement("plan", serverId, Json.decodeFromString<SyncPlanItem>(sent.payload.orEmpty()).baseVersion, version)
        planDao.updateVersion(serverId, version)
        pendingDao.delete(listOf(sent.id))
        pendingDao.all().filter { it.type == "plan_upsert" && it.planId == serverId }.forEach { p ->
            val item = Json.decodeFromString<SyncPlanItem>(p.payload.orEmpty())
            pendingDao.update(p.copy(payload = Json.encodeToString(item.copy(id = serverId, baseVersion = version))))
        }
    }

    suspend fun acknowledgeStrategy(sent: PendingRecordEntity, version: Long) = database.withTransaction {
        val planId = requireNotNull(sent.planId)
        recordAcknowledgement("strategy", planId, Json.decodeFromString<SyncStrategyItem>(sent.payload.orEmpty()).baseVersion, version)
        strategyDao.updateVersion(planId, version)
        pendingDao.delete(listOf(sent.id))
        pendingDao.all().filter { it.type == "strategy_upsert" && it.planId == planId }.forEach { p ->
            val item = Json.decodeFromString<SyncStrategyItem>(p.payload.orEmpty())
            pendingDao.update(p.copy(payload = Json.encodeToString(item.copy(baseVersion = version))))
        }
    }

    /** Upgrade marker-only installations once; the old local snapshot is the only recoverable payload. */
    suspend fun prepareOutbox() = database.withTransaction {
        // Old Guide snapshots stored comma-separated package/category tokens.
        strategyDao.all().forEach { old ->
            val normalized = StrategyWhitelistPayload.normalizeLegacy(old.whitelistApps)
            if (normalized != old.whitelistApps) strategyDao.upsertAll(listOf(old.copy(whitelistApps = normalized)))
        }
        pendingDao.all().filter { it.type == "strategy_upsert" }.forEach { row ->
            val old = Json.decodeFromString<SyncStrategyItem>(row.payload.orEmpty())
            val normalized = StrategyWhitelistPayload.normalizeLegacy(old.whitelistApps)
            if (normalized != old.whitelistApps) pendingDao.update(row.copy(payload = Json.encodeToString(old.copy(whitelistApps = normalized))))
        }
        val legacy = pendingDao.all().filter { it.type == SnapshotProtection.LOCAL_PLAN_CHANGE }
        legacy.groupBy { it.planId }.forEach { (id, rows) ->
            if (id != null) {
                val snapshot = planDao.byId(id)
                if (snapshot == null) pendingDao.insert(PendingRecordEntity(type = "plan_delete", planId = id, clientId = "legacy-delete-${rows.first().clientId}"))
                else updateLocalPlan(snapshot, strategyChanged = rows.any { LocalPlanChangeIntent.needsStrategyRetry(it, strategyDao.byPlanId(id) != null) })
            }
            pendingDao.delete(rows.map { it.id })
        }
        // BUG-005 installations could have unmarked negative creates.
        planDao.all().filter { it.id < 0 && pendingDao.all().none { p -> p.type == "plan_upsert" && p.planId == it.id } }.forEach { updateLocalPlan(it, strategyChanged = true) }
        pendingDao.all().filter { it.type == "schedule_upsert" }.forEach { p ->
            val item = Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty())
            if (item.request.clientId == null) pendingDao.update(p.copy(payload = Json.encodeToString(item.copy(request = item.request.copy(clientId = p.clientId)))))
        }
    }

    suspend fun replaceStrategyPlanId(localPlanId: Long, serverPlanId: Long) =
        strategyDao.replacePlanId(localPlanId, serverPlanId)

    suspend fun schedules(): List<ScheduleSnapshotEntity> = scheduleDao.all()
    suspend fun schedule(id: Long): ScheduleSnapshotEntity? = scheduleDao.byId(resolveScheduleId(id))
    suspend fun saveLocalSchedule(item: ScheduleSnapshotEntity) = scheduleDao.upsertAll(listOf(item))
    suspend fun deleteLocalSchedule(id: Long) = scheduleDao.delete(id)

    suspend fun saveScheduleChange(input: ScheduleSnapshotEntity, original: ScheduleUpsertRequest) = database.withTransaction {
        val canonicalId = resolveScheduleId(input.id)
        val linked = normalizedLinkedPlan(input.linkedPlanId)
        val snapshot = input.copy(id = canonicalId, version = if (canonicalId != input.id) scheduleDao.byId(canonicalId)?.version ?: input.version else requireNotNull(rebasedVersion("schedule", canonicalId, input.version, scheduleDao.byId(canonicalId)?.version)), linkedPlanId = linked, linkedPlanDeleted = input.linkedPlanDeleted || input.linkedPlanId != null && linked == null)
        val request = original.copy(linkedPlanId = normalizedLinkedPlan(original.linkedPlanId))
        require(PlanOccurrence.isSupportedRule(request.repeatRule)) { "暂不支持该重复规则" }
        scheduleDao.upsertAll(listOf(snapshot))
        enqueueScheduleUpsert(snapshot.id, snapshot.version.takeIf { snapshot.id > 0 }, request)
    }

    suspend fun enqueueScheduleUpsert(localId: Long, baseVersion: Long?, request: ScheduleUpsertRequest) = database.withTransaction {
        val marker = "schedule-${UUID.randomUUID()}"
        val previous = pendingDao.all().firstOrNull { it.type == "schedule_upsert" && Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).localId == localId }
        val identity = previous?.let { Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).request.clientId } ?: marker
        pendingDao.insert(PendingRecordEntity(type = "schedule_upsert", planId = null, clientId = marker, payload = Json.encodeToString(SchedulePendingUpsert(localId, baseVersion, request.copy(clientId = identity)))))
    }

    suspend fun enqueueScheduleDelete(inputId: Long) = database.withTransaction {
        val id = resolveScheduleId(inputId)
        pendingDao.insert(PendingRecordEntity(type = "schedule_delete", planId = null, clientId = "schedule-delete-${UUID.randomUUID()}", payload = id.toString()))
        scheduleDao.delete(id)
    }

    suspend fun acknowledgeSchedule(sent: PendingRecordEntity, serverId: Long, version: Long) = database.withTransaction {
        require(serverId > 0 && version >= 0)
        val item = Json.decodeFromString<SchedulePendingUpsert>(sent.payload.orEmpty())
        if (item.localId != serverId) {
            require(item.localId < 0 && resolveScheduleId(item.localId) in setOf(item.localId, serverId)) { "Conflicting schedule identity" }
            if (pendingDao.countByClientId("schedule-identity-${item.localId}") == 0) pendingDao.insert(PendingRecordEntity(type = "schedule_identity", planId = serverId, clientId = "schedule-identity-${item.localId}", payload = item.localId.toString()))
        }
        recordAcknowledgement("schedule", serverId, item.baseVersion, version)
        val current = scheduleDao.byId(item.localId)
        if (item.localId != serverId) scheduleDao.delete(serverId)
        if (current != null) {
            scheduleDao.delete(item.localId)
            scheduleDao.upsertAll(listOf(current.copy(id = serverId, version = version)))
        }
        pendingDao.delete(listOf(sent.id))
        pendingDao.all().forEach { p ->
            when (p.type) {
                "schedule_upsert" -> {
                    val later = Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty())
                    if (later.localId == item.localId) pendingDao.update(p.copy(payload = Json.encodeToString(later.copy(localId = serverId, baseVersion = version))))
                }
                "schedule_delete" -> if (p.payload == item.localId.toString()) pendingDao.update(p.copy(payload = serverId.toString()))
            }
        }
    }

    companion object {
        private val pendingMutex = Mutex()
        private val METADATA_TYPES = setOf("plan_identity", "schedule_identity", "local_confirmation")
    }
}

@kotlinx.serialization.Serializable
data class SchedulePendingUpsert(val localId: Long, val baseVersion: Long?, val request: ScheduleUpsertRequest)

private fun SyncScheduleView.toEntity() = ScheduleSnapshotEntity(
    id, userId, title, startDate, startTime, endDate, endTime, allDay, location, note,
    remindAdvanceMin, repeatRule, linkedPlanId, false, tzId, status, version,
)

private fun PlanSnapshotEntity.toSyncItem(identity: String) = SyncPlanItem(
    id = id.takeIf { it > 0 }, baseVersion = version.takeIf { id > 0 }, name = name, type = type,
    startTime = startTime, endTime = endTime, repeatRule = repeatRule, color = color, icon = icon,
    remindAdvanceMin = remindAdvanceMin, remindMethods = remindMethods, status = status, clientId = identity,
)
private fun StrategySnapshotEntity.toSyncItem() = SyncStrategyItem(planId, version.takeIf { planId > 0 }, null, StrategyWhitelistPayload.normalizeLegacy(whitelistApps), autoDnd, notifySilence)


/** One bounded provenance row per entity; never uploaded or counted as pending work. */
@kotlinx.serialization.Serializable
private data class LocalConfirmation(
    val kind: String,
    val fromVersion: Long,
    val toVersion: Long,
    val acceptsNullBase: Boolean = false,
    val deleted: Boolean = false,
)
