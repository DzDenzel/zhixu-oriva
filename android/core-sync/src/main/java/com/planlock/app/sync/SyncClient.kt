package com.planlock.app.sync

import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import com.planlock.app.record.entity.PendingRecordEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Existing sync owner drains the durable queue; failed entities never overtake their own intents. */
class SyncClient(private val api: PlanLockApi, private val recordStore: RecordStore, private val beforePlanMapping: suspend (Long, Long) -> Unit = { _, _ -> }) {
    suspend fun pull(): SyncPullResponse? = transferMutex.withLock {
        val response = api.syncPull()
        check(response.code == 0) { "同步服务返回失败" }
        val data = checkNotNull(response.data) { "同步快照缺失" }
        recordStore.saveSnapshots(data.plans.orEmpty(), data.strategies.orEmpty(), data.schedules.orEmpty())
        data
    }

    suspend fun pushPendingRecords() = transferMutex.withLock {
        recordStore.prepareOutbox()
        val blocked = mutableSetOf<String>()
        val initial = recordStore.pending()
        val creates = initial.filter { it.type == "plan_upsert" && (it.planId ?: 0) < 0 }.groupBy { it.planId }.values.map { rows -> rows.minBy { it.id }.id }.toSet()
        val initialIds = initial.sortedWith(compareBy<PendingRecordEntity> { if (it.id in creates) 0 else 1 }.thenBy { it.id }).map { it.id }
        for (id in initialIds) {
            // An earlier acknowledgement may have mapped IDs/rebased this persisted row.
            val p = recordStore.pendingById(id) ?: continue
            val entity = when (p.type) {
                "schedule_upsert" -> "schedule:${Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty()).localId}"
                "schedule_delete" -> "schedule:${p.payload}"
                else -> "plan:${p.planId}"
            }
            if (entity in blocked) continue
            val ok = try { send(p) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!ok) blocked += entity
        }
        check(recordStore.pending().isEmpty()) { "仍有本地更改或记录未补传" }
    }

    private suspend fun send(p: PendingRecordEntity): Boolean = when (p.type) {
        "plan_upsert" -> {
            val item = Json.decodeFromString<SyncPlanItem>(p.payload.orEmpty())
            val response = api.syncPush(SyncPushRequest(plans = listOf(item)))
            val result = response.data?.results?.singleOrNull()
            val valid = response.code == 0 && result?.ok == true && result.key == "plan:${item.id}" &&
                result.id != null && requireNotNull(result.id) > 0 && result.version != null && requireNotNull(result.version) >= 0 &&
                (item.id == null && result.clientId == item.clientId || item.id != null && result.id == item.id)
            if (valid) {
                if (requireNotNull(p.planId) < 0) beforePlanMapping(requireNotNull(p.planId), requireNotNull(result?.id))
                recordStore.acknowledgePlan(p, requireNotNull(result?.id), requireNotNull(result?.version)); true
            } else if (item.id != null) {
                val remote = remoteSnapshot()?.plans?.firstOrNull { it.id == item.id }
                if (remote != null && samePlan(item, remote) && remote.version != null && requireNotNull(remote.version) >= 0) {
                    recordStore.acknowledgePlan(p, remote.id, requireNotNull(remote.version)); true
                } else false
            } else false
        }
        "strategy_upsert" -> {
            val item = Json.decodeFromString<SyncStrategyItem>(p.payload.orEmpty())
            if (item.planId <= 0) false else {
                val response = api.syncPush(SyncPushRequest(strategies = listOf(item)))
                val result = response.data?.results?.singleOrNull()
                if (response.code == 0 && result?.ok == true && result.key == "strategy:${item.planId}" && result.version != null && requireNotNull(result.version) >= 0) {
                    recordStore.acknowledgeStrategy(p, requireNotNull(result.version)); true
                } else {
                    val remote = remoteSnapshot()?.strategies?.firstOrNull { it.planId == item.planId }
                    if (remote != null && sameStrategy(item, remote) && remote.version != null && requireNotNull(remote.version) >= 0) {
                        recordStore.acknowledgeStrategy(p, requireNotNull(remote.version)); true
                    } else false
                }
            }
        }
        "plan_delete" -> if (requireNotNull(p.planId) <= 0) false else finish(p, api.deletePlan(requireNotNull(p.planId)).code == 0)
        "schedule_upsert" -> {
            val item = Json.decodeFromString<SchedulePendingUpsert>(p.payload.orEmpty())
            if (item.request.linkedPlanId?.let { it <= 0 } == true) false else {
                val response = if (item.baseVersion == null) api.createSchedule(item.request)
                    else api.updateSchedule(item.localId, requireNotNull(item.baseVersion), item.request)
                val remote = response.data
                if (response.code == 0 && remote != null && remote.id > 0 && requireNotNull(remote.version) >= 0 && (item.baseVersion == null && remote.clientId == item.request.clientId || item.baseVersion != null && remote.id == item.localId)) {
                    recordStore.acknowledgeSchedule(p, remote.id, requireNotNull(remote.version)); true
                } else if (item.baseVersion != null) {
                    val current = remoteSnapshot()?.schedules?.firstOrNull { it.id == item.localId }
                    if (current != null && sameSchedule(item.request, current)) {
                        recordStore.acknowledgeSchedule(p, current.id, current.version); true
                    } else false
                } else false
            }
        }
        "schedule_delete" -> p.payload?.toLongOrNull()?.let { if (it <= 0) false else finish(p, api.deleteSchedule(it).code == 0) } ?: false
        else -> if (p.planId?.let { it <= 0 } == true) false else when (val upload = p.toPendingUpload()) {
            is PendingUpload.Execution -> finish(p, api.reportExecution(upload.request).code == 0)
            is PendingUpload.Violation -> finish(p, api.reportViolation(upload.request).code == 0)
            is PendingUpload.Unlock -> finish(p, api.reportUnlock(upload.request).code == 0)
            null -> false
        }
    }

    private suspend fun remoteSnapshot(): SyncPullResponse? = api.syncPull().let { if (it.code == 0) it.data else null }
    private suspend fun finish(p: PendingRecordEntity, ok: Boolean): Boolean {
        if (ok) recordStore.removePending(listOf(p.id))
        return ok
    }
    companion object { private val transferMutex = Mutex() }
}

internal fun samePlan(sent: SyncPlanItem, remote: SyncPlanView): Boolean =
    sent.name == remote.name && sent.type == remote.type && sent.startTime == remote.startTime && sent.endTime == remote.endTime &&
        sameJson(sent.repeatRule, remote.repeatRule) && sent.color == remote.color && sent.icon == remote.icon &&
        (sent.remindAdvanceMin ?: 15) == (remote.remindAdvanceMin ?: 15) && sameJson(sent.remindMethods, remote.remindMethods) &&
        (sent.status ?: 1) == (remote.status ?: 1)
internal fun sameStrategy(sent: SyncStrategyItem, remote: SyncStrategyView): Boolean =
    sameJson(sent.whitelistApps, remote.whitelistApps) && sent.autoDnd == remote.autoDnd && sent.notifySilence == remote.notifySilence
internal fun sameSchedule(sent: ScheduleUpsertRequest, remote: SyncScheduleView): Boolean =
    sent.title.trim() == remote.title && sent.startDate == remote.startDate && sameClock(sent.startTime, remote.startTime) && sent.endDate == remote.endDate &&
        sameClock(sent.endTime, remote.endTime) && sent.allDay == remote.allDay && sent.location == remote.location && sent.note == remote.note &&
        sent.remindAdvanceMin == remote.remindAdvanceMin && sameJson(sent.repeatRule, remote.repeatRule) && sent.linkedPlanId == remote.linkedPlanId && sent.tzId == remote.tzId
private fun sameJson(a: String?, b: String?): Boolean = if (a == b) true else try {
    a != null && b != null && Json.parseToJsonElement(a) == Json.parseToJsonElement(b)
} catch (_: Exception) { false }

private fun sameClock(a: String?, b: String?): Boolean {
    fun normalized(value: String?): String? = if (value != null && Regex("[0-2][0-9]:[0-5][0-9]").matches(value)) "$value:00" else value
    return normalized(a) == normalized(b)
}
