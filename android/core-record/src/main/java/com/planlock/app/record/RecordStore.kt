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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 本地记录采集 + 计划/策略快照缓存 + 离线补传队列（Room）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordStore(private val database: PlanLockDatabase, private val historyScope: () -> String = ::currentHistoryScope, private val cancelReminder: (Long) -> Unit = {}) {
    private val boundScope = ThreadLocal<String?>()
    internal fun dataScope(): String = boundScope.get() ?: historyScope()
    internal suspend fun <T> scopedTransaction(block: suspend () -> T): T =
        withContext(boundScope.asContextElement(dataScope())) { database.withTransaction { block() } }
    fun currentScope(): String = dataScope()
    /** Bind delayed telemetry to the identity that observed it, even after a sign-out. */
    fun forScope(scope: String): RecordStore = RecordStore(database, { scope }, cancelReminder)
    val localRevision get() = database.localRevision
    constructor(context: Context) : this(PlanLockDatabase.get(context), cancelReminder = { ScheduleReminderScheduler(context).cancel(it) })
    private val reconciler by lazy { SnapshotReconciler(database, this, cancelReminder) }
    fun observeConfirmations() = com.planlock.app.api.TokenStore.state.flatMapLatest { reconciler.observeConfirmations() }
    fun observeBulkDeletion() = com.planlock.app.api.TokenStore.state.flatMapLatest { reconciler.observeBulkDeletion() }
    suspend fun confirmations() = reconciler.confirmations()
    suspend fun bulkDeletion() = reconciler.bulkDeletion()
    suspend fun markConflict(row: PendingRecordEntity, remote: SyncPullResponse) = reconciler.markConflict(row, remote)
    suspend fun resolveConfirmation(expected: SyncConfirmation, keepMine: Boolean) = reconciler.resolve(expected, keepMine)
    suspend fun approveBulkDeletion(expected: BulkDeletion) = reconciler.approveBulkDeletion(expected)
    internal val planDao = ScopedPlans(database.planSnapshotDao(), ::dataScope)
    internal val strategyDao = ScopedStrategies(database.strategySnapshotDao(), ::dataScope)
    internal val pendingDao = ScopedPending(database.pendingRecordDao(), ::dataScope)
    internal val scheduleDao = ScopedSchedules(database.scheduleSnapshotDao(), ::dataScope)
    private val aggregateDao = ScopedAggregates(database.behaviorAggregateDao(), ::dataScope)
    private val retention = BehaviorRetentionPolicy()
    private val historyDao = database.executionHistoryDao()
    private val behaviorHistoryDao = database.behaviorHistoryDao()
    private val typeDao = database.planTypeSnapshotDao()

    /** Catalogs are always read in the same data scope as their plans. */
    suspend fun planTypes(): List<com.planlock.app.api.PlanTypeDefinition> = typeDao.all(dataScope()).filterNot { it.deleted }.map {
        com.planlock.app.api.PlanTypeDefinition(it.typeKey, it.name, it.color)
    }
    suspend fun planTypeLabel(value: String?): String = com.planlock.app.api.PlanTypes.label(value, planTypes())
    suspend fun createPlanType(name: String, color: String): String = scopedTransaction {
        val key = "custom:${UUID.randomUUID()}"
        savePlanType(key, name, color)
        key
    }
    suspend fun renamePlanType(key: String, name: String, color: String? = null) = scopedTransaction {
        val current = checkNotNull(typeDao.all(dataScope()).firstOrNull { it.typeKey == key && !it.deleted }) { "类型已不存在" }
        savePlanType(key, name, color ?: current.color)
    }
    /** UI confirms first; snapshot reassignment and its durable intents commit together. */
    suspend fun deletePlanType(key: String) = scopedTransaction {
        val current = checkNotNull(typeDao.all(dataScope()).firstOrNull { it.typeKey == key && !it.deleted }) { "类型已不存在" }
        reassignPlanType(key, enqueue = true)
        savePlanType(key, current.name, current.color, deleted = true)
    }
    private suspend fun savePlanType(key: String, name: String, color: String, deleted: Boolean = false) {
        require(key.startsWith("custom:") && key.length <= 64)
        require(color in com.planlock.app.api.PlanTypes.colors) { "无效类别色" }
        val label = com.planlock.app.api.PlanTypes.validateName(name)
        val version = typeDao.all(dataScope()).firstOrNull { it.typeKey == key }?.version ?: 0
        typeDao.upsert(listOf(com.planlock.app.record.entity.PlanTypeSnapshotEntity(dataScope(), key, label, color, version, deleted)))
        val mutation = "type-${UUID.randomUUID()}"
        pendingDao.insert(PendingRecordEntity(type = "plan_type_upsert", planId = null, clientId = mutation,
            payload = Json.encodeToString(SyncPlanTypeItem(key, label, color, deleted, mutation))))
    }
    private suspend fun reassignPlanType(key: String, enqueue: Boolean) {
        planDao.all().filter { it.type == key }.forEach {
            if (enqueue) updateLocalPlan(it.copy(type = "其他")) else planDao.upsertAll(listOf(it.copy(type = "其他")))
        }
        pendingDao.all().filter { it.type == "plan_upsert" }.forEach {
            val item = Json.decodeFromString<SyncPlanItem>(it.payload.orEmpty())
            if (item.type == key) pendingDao.update(it.copy(payload = Json.encodeToString(item.copy(type = "其他"))))
        }
    }
    suspend fun acknowledgePlanType(sent: PendingRecordEntity, version: Long) = scopedTransaction {
        check(sent.scope == dataScope()) { "上传身份已变化" }
        val item = Json.decodeFromString<SyncPlanTypeItem>(sent.payload.orEmpty())
        typeDao.all(dataScope()).firstOrNull { it.typeKey == item.typeKey }?.let { typeDao.upsert(listOf(it.copy(version = version))) }
        pendingDao.delete(listOf(sent.id))
    }
    private suspend fun mergePlanTypes(remote: List<SyncPlanTypeView>) {
        val pending = pendingDao.all().filter { it.type == "plan_type_upsert" }
        val keys = pending.map { Json.decodeFromString<SyncPlanTypeItem>(it.payload.orEmpty()).typeKey }.toSet()
        val existing = typeDao.all(dataScope()).associateBy { it.typeKey }
        remote.forEach { row ->
            if (row.deleted || row.typeKey !in keys) {
                val old = existing[row.typeKey]
                if (old == null || row.version >= old.version) typeDao.upsert(listOf(com.planlock.app.record.entity.PlanTypeSnapshotEntity(dataScope(), row.typeKey, row.name, row.color, row.version, row.deleted)))
            }
            if (row.deleted) {
                pendingDao.delete(pending.filter { Json.decodeFromString<SyncPlanTypeItem>(it.payload.orEmpty()).typeKey == row.typeKey }.map { it.id })
                reassignPlanType(row.typeKey, enqueue = false)
            }
        }
    }

    /** No pruning yet: retain all execution history (including at least the required 13 months). */
    suspend fun executionHistory(scope: String = dataScope()): List<ExecutionRecordDto> = scopedTransaction {
        seedLegacyHistory()
        historyDao.all(scope).filter { it.type == "execution" }.map {
            ExecutionRecordDto(it.serverId,it.planId,it.deviceId,it.planDate,it.status,it.startedAt,it.endedAt,it.clientId)
        }
    }
    /** Bounded, scoped display read. No migration, record creation, or scheduler mutation. */
    suspend fun executionHistoryForDates(fromDate: String, throughDate: String): List<com.planlock.app.record.entity.ExecutionHistoryEntity> =
        scopedTransaction { historyDao.forDates(dataScope(), fromDate, throughDate) }
    suspend fun unlockHistory(scope: String = dataScope()): List<UnlockRecordDto> = scopedTransaction {
        seedLegacyHistory()
        historyDao.all(scope).filter { it.type == "unlock" }.map {
            UnlockRecordDto(it.serverId,it.planId,it.unlockAt,it.method,it.clientId)
        }
    }
    suspend fun behaviorHistory(): List<RhythmBehavior> = scopedTransaction {
        behaviorHistoryDao.all(dataScope()).map { Json.decodeFromString<RhythmBehavior>(it.payload) }
    }
    private suspend fun seedLegacyHistory() {
        if(pendingDao.byClientId("history-migration-v7") != null) return
        pendingDao.all().filter { it.type in setOf("execution", "unlock") }.forEach { historyDao.insert(it.toHistory(dataScope())) }
        pendingDao.insert(PendingRecordEntity(type="history_seed",planId=null,clientId="history-migration-v7"))
    }
    suspend fun historyCursor() = historyDao.cursor(dataScope()) ?: com.planlock.app.record.entity.RecordHistoryCursor(dataScope())
    suspend fun mergeHistory(page: RecordHistoryPage) = scopedTransaction {
        val scope = dataScope()
        val localKeys=historyDao.all(scope).filter { it.localOrigin }.mapTo(mutableSetOf()) { it.key }
        val incoming=page.executions.map { e -> com.planlock.app.record.entity.ExecutionHistoryEntity(scope, historyKey("execution", e.clientId, e.id),"execution",e.id,e.planId,e.planDate,e.status,e.startedAt,e.endedAt,clientId=e.clientId,deviceId=e.deviceId) } +
            page.unlocks.map { u -> com.planlock.app.record.entity.ExecutionHistoryEntity(scope,historyKey("unlock",u.clientId,u.id),"unlock",u.id,u.planId,unlockAt=u.unlockAt,method=u.method,clientId=u.clientId) }
        historyDao.upsert(incoming.map { it.copy(localOrigin=it.key in localKeys) })
        val localBehaviorKeys = behaviorHistoryDao.all(scope).filter { it.localOrigin }.mapTo(mutableSetOf()) { it.key }
        behaviorHistoryDao.upsert(page.behaviors.map { row ->
            val key = historyKey("violation", row.clientId, row.id)
            com.planlock.app.record.entity.BehaviorHistoryEntity(scope, key, row.planId, row.id,
                Json.encodeToString(row), key in localBehaviorKeys)
        })
        historyDao.saveCursor(com.planlock.app.record.entity.RecordHistoryCursor(scope,page.executionCursor,page.unlockCursor,page.behaviorCursor))
    }
    private suspend fun moveBehaviorScope(source: String, destination: String) {
        behaviorHistoryDao.all(source).forEach { behaviorHistoryDao.insert(it.copy(scope = destination)) }
        behaviorHistoryDao.deleteScope(source)
    }
    private suspend fun mapBehaviorPlan(oldId: Long, newId: Long) {
        behaviorHistoryDao.upsert(behaviorHistoryDao.all(dataScope()).filter { it.planId == oldId }.map { row ->
            val payload = Json.decodeFromString<RhythmBehavior>(row.payload).copy(planId = newId)
            row.copy(planId = newId, payload = Json.encodeToString(payload))
        })
    }
    /** Registration binds previously unowned local history once, alongside the existing data owner. */
    suspend fun claimUnownedHistory(instance: String) = scopedTransaction {
        seedLegacyHistory()
        val destination = dataScope()
        listOf(historyScopeKey("local",null),historyScopeKey(instance,null)).distinct().filter { it != destination }.forEach { source ->
            historyDao.all(source).forEach { historyDao.insert(it.copy(scope=destination)) }
            historyDao.deleteScope(source)
            moveBehaviorScope(source, destination)
        }
    }
    /** Invoke only after the backend explicitly acknowledges the guest merge. */
    suspend fun claimMergedHistory(instance: String, guestId: Long) = scopedTransaction {
        seedLegacyHistory()
        val source=historyScopeKey(instance,guestId)
        val destination=dataScope()
        if(source != destination) {
            historyDao.all(source).forEach { historyDao.insert(it.copy(scope=destination)) }
            historyDao.deleteScope(source)
            moveBehaviorScope(source, destination)
        }
    }

    suspend fun guestContentCount(scopes: List<String>): Int = scopes.distinct().sumOf { source ->
        database.planSnapshotDao().all(source).size + database.scheduleSnapshotDao().all(source).size + typeDao.all(source).count { !it.deleted }
    }

    /** Session owner invokes this only after a successful server merge (including local-only guests). */
    suspend fun acceptGuestData(scopes: List<String>) = scopedTransaction {
        val destination = dataScope()
        val owner = scopeOwner(destination)
        for (source in scopes.distinct().filter { it != destination }) {
            val sourcePlans = database.planSnapshotDao().all(source)
            val sourceSchedules = database.scheduleSnapshotDao().all(source)
            val destinationTypes = typeDao.all(destination).associateBy { it.typeKey }
            typeDao.all(source).forEach { type ->
                val existing = destinationTypes[type.typeKey]
                if (existing == null || type.deleted && !existing.deleted) typeDao.upsert(listOf(type.copy(scope = destination)))
            }
            typeDao.clear(source)
            check(sourcePlans.none { row -> planDao.all().any { it.id == row.id } } &&
                sourceSchedules.none { row -> scheduleDao.all().any { it.id == row.id } }) { "合并身份冲突，游客内容已保留" }
            // SQL identifiers are a fixed internal list; values are always bound parameters.
            val sql = database.openHelper.writableDatabase
            listOf("plan_snapshot", "schedule_snapshot").forEach {
                sql.execSQL("UPDATE $it SET scope=?,userId=? WHERE scope=?", arrayOf(destination, owner, source))
            }
            listOf("strategy_snapshot", "pending_record", "behavior_aggregate").forEach {
                sql.execSQL("UPDATE $it SET scope=? WHERE scope=?", arrayOf(destination, source))
            }
            historyDao.all(source).forEach { historyDao.insert(it.copy(scope = destination)) }
            historyDao.deleteScope(source)
            moveBehaviorScope(source, destination)
        }
        historyDao.saveCursor(com.planlock.app.record.entity.RecordHistoryCursor(destination))
        typeDao.all(destination).filter { it.deleted }.forEach { reassignPlanType(it.typeKey, enqueue = false) }
    }

    suspend fun activeExecution(planId: Long): ExecutionRecordRequest? = scopedTransaction {
        seedLegacyHistory()
        val id = resolvePlanId(planId)
        val rows = historyDao.all(dataScope()).filter { it.type=="execution" && it.planId == id && it.localOrigin }
        rows.filter { it.status == 0 && rows.none { end -> end.clientId == it.clientId?.removeSuffix("-start")+"-end" && end.status in listOf(1,2,3) } }
            .maxByOrNull { it.startedAt.orEmpty() }?.let { ExecutionRecordRequest(id,it.planDate.orEmpty(),0,it.startedAt,clientId=requireNotNull(it.clientId)) }
    }
    suspend fun beginOccurrence(planId: Long, nowMillis: Long): Boolean = scopedTransaction {
        val plan = plan(planId) ?: return@scopedTransaction false
        val clock = java.util.Calendar.getInstance().apply { timeInMillis=nowMillis }
        val minutes = clock.get(java.util.Calendar.HOUR_OF_DAY)*60+clock.get(java.util.Calendar.MINUTE)
        if (!PlanOccurrence.isActive(plan,minutes,OccurrenceDate.from(clock))) return@scopedTransaction false
        val start = RecordEventFactory().executionStarted(plan,nowMillis).copy(clientId="exec-${UUID.randomUUID()}-start")
        seedLegacyHistory()
        val rows = historyDao.all(dataScope()).filter { it.type=="execution" && it.localOrigin && it.planId==plan.id }
        if (rows.any { it.planDate==start.planDate && it.status in listOf(1,2) }) return@scopedTransaction false
        activeExecution(planId)?.let {
            if(it.planDate==start.planDate) return@scopedTransaction true
            finishOccurrence(planId,nowMillis)
        }
        enqueueExecution(start)
        true
    }
    suspend fun setPlanEnabled(planId: Long, enabled: Boolean, nowMillis: Long): Unit = scopedTransaction {
        val current = checkNotNull(plan(planId)) { "计划已不存在" }
        val status = if(enabled) 1 else 0
        if(current.status != status) updateLocalPlan(current.copy(status=status,statusLog=appendStatus(current.statusLog,nowMillis,status)))
    }
    suspend fun endOccurrence(planId: Long, nowMillis: Long, endEntirePlan: Boolean, method: String): Boolean = scopedTransaction {
        val current = plan(planId) ?: return@scopedTransaction false
        val clock = java.util.Calendar.getInstance().apply { timeInMillis=nowMillis }
        val minutes=clock.get(java.util.Calendar.HOUR_OF_DAY)*60+clock.get(java.util.Calendar.MINUTE)
        if(current.status != 1 || !PlanOccurrence.isWithinOccurrence(current,minutes,OccurrenceDate.from(clock))) return@scopedTransaction false
        val expected = RecordEventFactory().executionStarted(current,nowMillis).copy(clientId="exec-${UUID.randomUUID()}-start")
        seedLegacyHistory()
        if(current.endedOccurrenceDate==expected.planDate || historyDao.all(dataScope()).any { it.type=="execution" && it.localOrigin && it.planId==current.id && it.planDate==expected.planDate && it.status in listOf(1,2) }) return@scopedTransaction false
        val previous = activeExecution(planId)
        if(previous != null && previous.planDate != expected.planDate) finishOccurrence(planId,nowMillis)
        val start = previous?.takeIf { it.planDate==expected.planDate } ?: expected.also { enqueueExecution(it) }
        enqueueExecution(terminal(current,start,nowMillis,false))
        enqueueUnlock(RecordEventFactory().unlock(current.id,nowMillis,method).copy(clientId="${start.clientId}-unlock"))
        updateLocalPlan(current.copy(endedOccurrenceDate=start.planDate,status=if(endEntirePlan) 0 else 1,
            statusLog=if(endEntirePlan) appendStatus(current.statusLog,nowMillis,0) else current.statusLog))
        true
    }
    suspend fun finishOccurrence(planId: Long, nowMillis: Long): Boolean = scopedTransaction {
        val current = plan(planId) ?: return@scopedTransaction false
        val start=activeExecution(planId) ?: return@scopedTransaction false
        val scheduledEnd=occurrenceEndMillis(current,start.planDate)
        val completed=current.status==1 && nowMillis >= scheduledEnd
        enqueueExecution(terminal(current,start,if(completed) scheduledEnd else nowMillis,completed))
        true
    }
    private fun terminal(plan: PlanSnapshotEntity, start: ExecutionRecordRequest, now: Long, completed: Boolean) =
        RecordEventFactory().executionEnded(plan,now,completed).copy(planDate=start.planDate,startedAt=start.startedAt,clientId=start.clientId.removeSuffix("-start")+"-end")
    private fun occurrenceEndMillis(plan: PlanSnapshotEntity, date: String): Long {
        val end=requireNotNull(plan.endTime).take(5)
        val parsed=requireNotNull(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).parse("$date $end"))
        return java.util.Calendar.getInstance().apply {
            time=parsed
            if(end < requireNotNull(plan.startTime).take(5)) add(java.util.Calendar.DAY_OF_MONTH,1)
        }.timeInMillis
    }

    /** Caller must supply a complete, successful, owner-checked snapshot. */
    suspend fun saveSnapshots(plans: List<SyncPlanView>, strategies: List<SyncStrategyView>, schedules: List<SyncScheduleView> = emptyList(), planTypes: List<SyncPlanTypeView> = emptyList()) =
        scopedTransaction {
            val knownIds=planDao.all().mapTo(mutableSetOf()) { it.id }
            // Guest merges can transfer old record IDs to this owner. A newly visible plan
            // invalidates the append cursor; history keys make the replay idempotent.
            if(plans.any { it.id !in knownIds }) historyDao.saveCursor(com.planlock.app.record.entity.RecordHistoryCursor(dataScope()))
            reconciler.reconcile(plans, strategies, schedules)
            mergePlanTypes(planTypes)
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
        if (com.planlock.app.api.HostApplication.owns(req.packageName)) return
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

    suspend fun pending(): List<PendingRecordEntity> {
        val rows = pendingDao.all()
        val blocked = rows.filter { it.type == "sync_conflict" }.map { Json.decodeFromString<SyncConfirmation>(it.payload.orEmpty()).key }.toSet()
        return rows.filterNot { it.type in METADATA_TYPES || it.syncEntityKey() in blocked }
    }

    suspend fun pendingById(id: Long): PendingRecordEntity? {
        val row = pendingDao.byId(id) ?: return null
        if (row.type in METADATA_TYPES) return null
        val key = row.syncEntityKey()
        return row.takeUnless { key != null && pendingDao.byClientId("conflict:$key") != null }
    }

    private suspend fun resolveId(id: Long, type: String): Long {
        if (id > 0) return id
        val aliases = pendingDao.all().filter { it.type == type && it.payload == id.toString() }
        check(aliases.mapNotNull { it.planId }.distinct().size <= 1) { "Conflicting identity mapping" }
        return aliases.firstOrNull()?.planId?.also { require(it > 0) { "Identity mapping must be canonical" } } ?: id
    }
    private suspend fun resolveRestoredId(id: Long, kind: String): Long {
        var current = resolveId(id, "${kind}_identity")
        val visited = mutableSetOf<Long>()
        while (visited.add(current)) {
            val next = pendingDao.byClientId("$kind-restoration:$current")?.planId ?: return current
            current = resolveId(next, "${kind}_identity")
        }
        error("Conflicting restored identity")
    }
    suspend fun resolvePlanId(id: Long): Long = resolveRestoredId(id, "plan")
    suspend fun resolveScheduleId(id: Long): Long = resolveRestoredId(id, "schedule")

    internal suspend fun rememberPlanDeleted(id: Long) {
        val previous = confirmation("plan", id) ?: LocalConfirmation("plan", 0, 0)
        saveConfirmation("plan", id, previous.copy(deleted = true))
    }
    internal suspend fun rememberRestoredSchedule(oldId: Long, newId: Long) {
        pendingDao.insert(PendingRecordEntity(type = "schedule_restoration", planId = newId, clientId = "schedule-restoration:$oldId", payload = oldId.toString()))
    }
    internal suspend fun moveRestoredPlanRecords(oldId: Long, newId: Long) {
        historyDao.mapPlan(dataScope(),oldId,newId)
        mapBehaviorPlan(oldId, newId)
        pendingDao.insert(PendingRecordEntity(type = "plan_restoration", planId = newId, clientId = "plan-restoration:$oldId", payload = oldId.toString()))
        pendingDao.all().filter { it.planId == oldId && it.type in setOf("execution", "violation", "unlock") }.forEach {
            pendingDao.update(it.copy(planId = newId))
        }
        aggregateDao.all().filter { it.planId == oldId }.forEach { aggregateDao.upsertAll(listOf(it.copy(planId = newId))) }
        aggregateDao.deleteByPlanId(oldId)
    }

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

    private suspend fun insertPending(record: PendingRecordEntity) = scopedTransaction {
        seedLegacyHistory()
        if (pendingDao.countByClientId(record.clientId) != 0) return@scopedTransaction
        // Resolve under the same Room transaction as insertion and aggregation.
        val canonical = record.copy(planId = record.planId?.let { resolvePlanId(it) })
        pendingDao.insert(canonical)
        if(canonical.type in setOf("execution","unlock")) historyDao.insert(canonical.toHistory(dataScope()))
        if (canonical.type == "violation") {
            val behavior = RhythmBehavior(clientId = canonical.clientId, planId = requireNotNull(canonical.planId),
                recordAt = canonical.recordAt, appName = canonical.appName, packageName = canonical.packageName,
                startedAt = canonical.startedAt, endedAt = canonical.endedAt, durationMs = canonical.durationMs, sourceLevel = canonical.sourceLevel)
            behaviorHistoryDao.insert(com.planlock.app.record.entity.BehaviorHistoryEntity(dataScope(),
                historyKey("violation", behavior.clientId, 0), behavior.planId, 0, Json.encodeToString(behavior), true))
        }
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

    // ---- 本地为源闭环（BUG-005）：计划/策略先落 Room，网络可用时再同步 ----

    suspend fun saveLocalPlan(plan: PlanSnapshotEntity) = planDao.upsertAll(listOf(plan))

    suspend fun saveLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun plans(): List<PlanSnapshotEntity> = planDao.all()

    suspend fun plan(id: Long): PlanSnapshotEntity? = planDao.byId(resolvePlanId(id))

    suspend fun strategy(planId: Long): StrategySnapshotEntity? = strategyDao.byPlanId(resolvePlanId(planId))

    /** Commit the visible snapshot and immutable transmission intent in one Room transaction. */
    suspend fun updateLocalPlan(input: PlanSnapshotEntity, strategyChanged: Boolean = false, strategy: StrategySnapshotEntity? = null): String = scopedTransaction {
        val canonicalId = resolvePlanId(input.id)
        val deletedType = typeDao.all(dataScope()).any { it.typeKey == input.type && it.deleted }
        val plan = input.copy(id = canonicalId, type = if (deletedType) "其他" else input.type, version = if (canonicalId != input.id) planDao.byId(canonicalId)?.version ?: input.version else rebasedVersion("plan", canonicalId, input.version, planDao.byId(canonicalId)?.version))
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
    suspend fun hasPendingStrategyChange(planId: Long): Boolean = scopedTransaction {
        val hasStrategy = strategyDao.byPlanId(planId) != null
        pendingDao.all().any { it.planId == planId && (it.type == "strategy_upsert" || LocalPlanChangeIntent.needsStrategyRetry(it, hasStrategy)) }
    }

    /** Clear acknowledged changes only; later edits and unacknowledged strategies stay protected. */
    suspend fun confirmLocalPlanChange(marker: String, strategyConfirmed: Boolean = false) = scopedTransaction {
        val changes = pendingDao.all().filter { it.type == SnapshotProtection.LOCAL_PLAN_CHANGE }
        val confirmed = changes.firstOrNull { it.clientId == marker } ?: return@scopedTransaction
        val hasStrategy = confirmed.planId?.let { strategyDao.byPlanId(it) } != null
        pendingDao.delete(changes.filter {
            it.planId == confirmed.planId && it.id <= confirmed.id &&
                LocalPlanChangeIntent.mayClear(it, hasStrategy, strategyConfirmed)
        }.map { it.id })
    }

    suspend fun saveConfirmedVersions(id: Long, planVersion: Long?, strategyVersion: Long?) = scopedTransaction {
        planVersion?.let { planDao.updateVersion(id, it) }
        strategyVersion?.let { strategyDao.updateVersion(id, it) }
    }

    suspend fun updateLocalStrategy(strategy: StrategySnapshotEntity) =
        strategyDao.upsertAll(listOf(strategy))

    suspend fun deletePlan(inputId: Long): String = scopedTransaction {
        val id = resolvePlanId(inputId)
        val marker = "plan-delete-${UUID.randomUUID()}"
        pendingDao.insert(PendingRecordEntity(type = "plan_delete", planId = id, clientId = marker, baseVersion = planDao.byId(id)?.version, localSnapshot = Json.encodeToString(DeletedSnapshot(plan = planDao.byId(id), strategy = strategyDao.byPlanId(id)))))
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
    suspend fun replacePlanId(localId: Long, serverId: Long) = scopedTransaction { mapPlanId(localId, serverId) }

    private suspend fun mapPlanId(localId: Long, serverId: Long) {
        require(serverId > 0)
        if (localId == serverId) return
        require(localId < 0 && resolvePlanId(localId) in setOf(localId, serverId)) { "Conflicting plan identity" }
        if (pendingDao.countByClientId("plan-identity-$localId") == 0) pendingDao.insert(PendingRecordEntity(type = "plan_identity", planId = serverId, clientId = "plan-identity-$localId", payload = localId.toString()))
        planDao.delete(serverId)
        planDao.replaceId(localId, serverId)
        historyDao.mapPlan(dataScope(),localId,serverId)
        mapBehaviorPlan(localId, serverId)
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
        // A restored backup can reuse the old numeric ID; a self-alias must disappear.
        pendingDao.all().filter { it.type == "plan_restoration" && it.planId == localId && it.payload == serverId.toString() }.forEach { pendingDao.delete(listOf(it.id)) }
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
    suspend fun acknowledgePlan(sent: PendingRecordEntity, serverId: Long, version: Long) = scopedTransaction {
        check(sent.scope == dataScope()) { "上传身份已变化" }
        val localId = requireNotNull(sent.planId)
        if (localId < 0) mapPlanId(localId, serverId)
        recordAcknowledgement("plan", serverId, Json.decodeFromString<SyncPlanItem>(sent.payload.orEmpty()).baseVersion, version)
        planDao.updateVersion(serverId, version)
        pendingDao.delete(listOf(sent.id))
        pendingDao.all().filter { it.type == "plan_delete" && it.planId == serverId }.forEach { pendingDao.update(it.copy(baseVersion = version)) }
        pendingDao.all().filter { it.type == "plan_upsert" && it.planId == serverId }.forEach { p ->
            val item = Json.decodeFromString<SyncPlanItem>(p.payload.orEmpty())
            pendingDao.update(p.copy(payload = Json.encodeToString(item.copy(id = serverId, baseVersion = version))))
        }
    }

    suspend fun acknowledgeStrategy(sent: PendingRecordEntity, version: Long) = scopedTransaction {
        check(sent.scope == dataScope()) { "上传身份已变化" }
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
    suspend fun prepareOutbox() = scopedTransaction {
        seedLegacyHistory()
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

    suspend fun saveScheduleChange(input: ScheduleSnapshotEntity, original: ScheduleUpsertRequest) = scopedTransaction {
        require(com.planlock.app.api.validCoordinates(original.locationLat, original.locationLon)) { "地点坐标无效" }
        val canonicalId = resolveScheduleId(input.id)
        val linked = normalizedLinkedPlan(input.linkedPlanId)
        val snapshot = input.copy(id = canonicalId, version = if (canonicalId != input.id) scheduleDao.byId(canonicalId)?.version ?: input.version else requireNotNull(rebasedVersion("schedule", canonicalId, input.version, scheduleDao.byId(canonicalId)?.version)), linkedPlanId = linked, linkedPlanDeleted = input.linkedPlanDeleted || input.linkedPlanId != null && linked == null)
        val request = original.copy(linkedPlanId = normalizedLinkedPlan(original.linkedPlanId))
        require(PlanOccurrence.isSupportedRule(request.repeatRule)) { "暂不支持该重复规则" }
        scheduleDao.upsertAll(listOf(snapshot))
        enqueueScheduleUpsert(snapshot.id, snapshot.version.takeIf { snapshot.id > 0 }, request)
    }

    suspend fun enqueueScheduleUpsert(localId: Long, baseVersion: Long?, request: ScheduleUpsertRequest) = scopedTransaction {
        val marker = "schedule-${UUID.randomUUID()}"
        val previous = pendingDao.all().firstOrNull { it.type == "schedule_upsert" && Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).localId == localId }
        val identity = previous?.let { Json.decodeFromString<SchedulePendingUpsert>(it.payload.orEmpty()).request.clientId } ?: marker
        pendingDao.insert(PendingRecordEntity(type = "schedule_upsert", planId = null, clientId = marker, payload = Json.encodeToString(SchedulePendingUpsert(localId, baseVersion, request.copy(clientId = identity)))))
    }

    suspend fun enqueueScheduleDelete(inputId: Long, expectedScope: String? = null) = scopedTransaction {
        check(expectedScope == null || expectedScope == dataScope()) { "账号已变化，请重新打开编辑页" }
        val id = resolveScheduleId(inputId)
        pendingDao.insert(PendingRecordEntity(type = "schedule_delete", planId = null, clientId = "schedule-delete-${UUID.randomUUID()}", payload = id.toString(), baseVersion = scheduleDao.byId(id)?.version, localSnapshot = Json.encodeToString(DeletedSnapshot(schedule = scheduleDao.byId(id)))))
        scheduleDao.delete(id)
    }

    suspend fun acknowledgeSchedule(sent: PendingRecordEntity, serverId: Long, version: Long) = scopedTransaction {
        check(sent.scope == dataScope()) { "上传身份已变化" }
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
                "schedule_restoration" -> if (p.planId == item.localId) {
                    if (p.payload == serverId.toString()) pendingDao.delete(listOf(p.id))
                    else pendingDao.update(p.copy(planId = serverId))
                }
                "schedule_upsert" -> {
                    val later = Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty())
                    if (later.localId == item.localId) pendingDao.update(p.copy(payload = Json.encodeToString(later.copy(localId = serverId, baseVersion = version))))
                }
                "schedule_delete" -> if (p.payload == item.localId.toString()) pendingDao.update(p.copy(payload = serverId.toString(), baseVersion = version))
            }
        }
    }

    companion object {
        private val pendingMutex = Mutex()
        private val METADATA_TYPES = setOf("plan_identity", "schedule_identity", "local_confirmation", "sync_conflict", "bulk_delete", "plan_restoration", "schedule_restoration", "history_seed")
    }
}

@kotlinx.serialization.Serializable
data class SchedulePendingUpsert(val localId: Long, val baseVersion: Long?, val request: ScheduleUpsertRequest)

private fun SyncScheduleView.toEntity() = ScheduleSnapshotEntity(
    id, userId, title, startDate, startTime, endDate, endTime, allDay, location, note,
    remindAdvanceMin, repeatRule, linkedPlanId, false, tzId, status, version,
    locationLat = locationLat, locationLon = locationLon,
)

private fun PlanSnapshotEntity.toSyncItem(identity: String) = SyncPlanItem(
    id = id.takeIf { it > 0 }, baseVersion = version.takeIf { id > 0 }, name = name, type = type,
    startTime = startTime, endTime = endTime, repeatRule = repeatRule, color = color, icon = icon,
    remindAdvanceMin = remindAdvanceMin, remindMethods = remindMethods, status = status, clientId = identity,
    endedOccurrenceDate = endedOccurrenceDate, statusLog = statusLog,
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
