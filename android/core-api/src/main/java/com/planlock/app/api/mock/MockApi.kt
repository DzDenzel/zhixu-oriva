package com.planlock.app.api.mock

import com.planlock.app.api.JournalExportItem
import com.planlock.app.api.JournalRules
import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.AuthResponse
import com.planlock.app.api.dto.BackupPasswordResetRequest
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.dto.DeviceRegisterRequest
import com.planlock.app.api.dto.ExecutionRecordRequest
import com.planlock.app.api.dto.JournalDto
import com.planlock.app.api.dto.JournalUpsertRequest
import com.planlock.app.api.dto.LoginRequest
import com.planlock.app.api.dto.MergeRequest
import com.planlock.app.api.dto.PlanDto
import com.planlock.app.api.dto.PlanUpsertRequest
import com.planlock.app.api.dto.ScheduleDto
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.api.dto.RegisterRequest
import com.planlock.app.api.dto.SendCodeRequest
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.StrategyDto
import com.planlock.app.api.dto.StrategyUpsertRequest
import com.planlock.app.api.dto.SyncItemResult
import com.planlock.app.api.dto.SyncJournalView
import com.planlock.app.api.dto.SyncPullResponse
import com.planlock.app.api.dto.SyncPushRequest
import com.planlock.app.api.dto.SyncPushResponse
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.api.dto.UnlockRecordRequest
import com.planlock.app.api.dto.ViolationRecordRequest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 开发期 mock 实现：契约一致，返回固定数据，供 UI 并行开发。
 */
class MockApi : PlanLockApi {

    private val token = AuthResponse(token = "mock-token", userId = 1L, deviceId = 1L, guestUserId = null)

    private var nextPlanId = 1000L
    private var nextScheduleId = 2000L
    private var nextJournalId = 3000L
    private val scheduleItems = mutableListOf<ScheduleDto>()
    private val journalItems = mutableListOf<JournalDto>()

    override suspend fun deviceRegister(req: DeviceRegisterRequest): ApiResponse<AuthResponse> =
        ApiResponse(0, "ok", token)

    override suspend fun sendCode(req: SendCodeRequest): ApiResponse<Unit> =
        ApiResponse(0, "ok", Unit)

    override suspend fun register(req: RegisterRequest): ApiResponse<AuthResponse> =
        ApiResponse(0, "ok", token)

    override suspend fun login(req: LoginRequest): ApiResponse<AuthResponse> =
        ApiResponse(0, "ok", token)

    override suspend fun merge(req: MergeRequest): ApiResponse<Map<String, Long>> =
        ApiResponse(0, "ok", mapOf("mergedPlans" to 0L))

    override suspend fun devices(): ApiResponse<List<DeviceDto>> =
        ApiResponse(0, "ok", listOf(DeviceDto(1, 1, "当前 Android 设备", "android", isPrimary = true, online = true)))

    override suspend fun setPrimaryDevice(id: Long): ApiResponse<Unit> = ApiResponse(0, "ok", Unit)

    override suspend fun unbindDevice(id: Long): ApiResponse<Unit> = ApiResponse(0, "ok", Unit)

    override suspend fun setBackupPassword(req: BackupPasswordSetRequest): ApiResponse<Unit> = ApiResponse(0, "ok", Unit)

    override suspend fun resetBackupPassword(req: BackupPasswordResetRequest): ApiResponse<Unit> = ApiResponse(0, "ok", Unit)

    override suspend fun plans(): ApiResponse<List<PlanDto>> = ApiResponse(0, "ok", emptyList())

    override suspend fun createPlan(req: PlanUpsertRequest): ApiResponse<PlanDto> =
        ApiResponse(0, "ok", PlanDto(id = ++nextPlanId, name = req.name, type = req.type))

    override suspend fun updatePlan(id: Long, baseVersion: Long, req: PlanUpsertRequest): ApiResponse<PlanDto> =
        ApiResponse(0, "ok", PlanDto(id = id, name = req.name, type = req.type))

    override suspend fun deletePlan(id: Long, baseVersion: Long?): ApiResponse<Unit> = ApiResponse(0, "ok", Unit)

    override suspend fun pausePlan(id: Long): ApiResponse<PlanDto> =
        ApiResponse(0, "ok", PlanDto(id = id))

    override suspend fun schedules(): ApiResponse<List<ScheduleDto>> = ApiResponse(0,"ok",scheduleItems.toList())
    override suspend fun createSchedule(req: ScheduleUpsertRequest): ApiResponse<ScheduleDto> {
        val item=ScheduleDto(id=++nextScheduleId,userId=1,title=req.title,startDate=req.startDate,startTime=req.startTime,endDate=req.endDate,endTime=req.endTime,allDay=req.allDay,location=req.location,note=req.note,remindAdvanceMin=req.remindAdvanceMin,repeatRule=req.repeatRule,linkedPlanId=req.linkedPlanId,tzId=req.tzId)
        scheduleItems += item; return ApiResponse(0,"ok",item)
    }
    override suspend fun updateSchedule(id: Long,baseVersion: Long,req: ScheduleUpsertRequest): ApiResponse<ScheduleDto> {
        val item=ScheduleDto(id=id,userId=1,title=req.title,startDate=req.startDate,startTime=req.startTime,endDate=req.endDate,endTime=req.endTime,allDay=req.allDay,location=req.location,note=req.note,remindAdvanceMin=req.remindAdvanceMin,repeatRule=req.repeatRule,linkedPlanId=req.linkedPlanId,tzId=req.tzId,version=baseVersion+1)
        scheduleItems.removeAll { it.id==id }; scheduleItems += item; return ApiResponse(0,"ok",item)
    }
    override suspend fun deleteSchedule(id: Long, baseVersion: Long?): ApiResponse<Unit> { scheduleItems.removeAll { it.id==id }; return ApiResponse(0,"ok",Unit) }

    override suspend fun strategy(planId: Long): ApiResponse<StrategyDto> =
        ApiResponse(0, "ok", StrategyDto(planId = planId))

    override suspend fun upsertStrategy(planId: Long, baseVersion: Long?, req: StrategyUpsertRequest): ApiResponse<StrategyDto> =
        ApiResponse(0, "ok", StrategyDto(planId = planId, blacklistApps = req.blacklistApps, whitelistApps = req.whitelistApps))

    override suspend fun today(): ApiResponse<TodaySummary> =
        ApiResponse(0, "ok", TodaySummary())

    override suspend fun stats(period: String): ApiResponse<StatsResponse> =
        ApiResponse(0, "ok", StatsResponse(period = period))

    override suspend fun rhythm(days: Int, timeZone: String) = ApiResponse(data = com.planlock.app.api.dto.RhythmSummary(
        "还没有执行记录，开始一个计划后这里会出现你的节奏。", 0, com.planlock.app.api.dto.Ratio(), com.planlock.app.api.dto.Ratio(),
        0, 0, 0, 0, emptyList(), emptyList()))

    override suspend fun recordHistory(executionAfterId: Long, unlockAfterId: Long, limit: Int, behaviorAfterId: Long) =
        ApiResponse(data = com.planlock.app.api.dto.RecordHistoryPage(executionCursor = executionAfterId, unlockCursor = unlockAfterId, behaviorCursor = behaviorAfterId))

    override suspend fun exportRecords(format: String): ResponseBody =
        (if (format == "csv") "date,completed,violations\n" else "[]").toResponseBody()

    override suspend fun exportPlans(): ResponseBody = "BEGIN:VCALENDAR\nEND:VCALENDAR".toResponseBody()

    override suspend fun reportExecution(req: ExecutionRecordRequest): ApiResponse<Unit> =
        ApiResponse(0, "ok", Unit)

    override suspend fun reportViolation(req: ViolationRecordRequest): ApiResponse<Unit> =
        ApiResponse(0, "ok", Unit)

    override suspend fun reportUnlock(req: UnlockRecordRequest): ApiResponse<Unit> =
        ApiResponse(0, "ok", Unit)

    override suspend fun syncPull(): ApiResponse<SyncPullResponse> =
        ApiResponse(
            0,
            "ok",
            SyncPullResponse(
                plans = emptyList(),
                schedules = emptyList(),
                strategies = emptyList(),
                journals = journalItems.map {
                    SyncJournalView(
                        id = it.id,
                        userId = it.userId,
                        clientId = it.clientId,
                        entryDate = it.entryDate,
                        mood = it.mood,
                        tags = it.tags,
                        content = it.content,
                        status = it.status,
                        version = it.version,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                    )
                },
            ),
        )

    override suspend fun syncPush(req: SyncPushRequest): ApiResponse<SyncPushResponse> {
        val results = mutableListOf<SyncItemResult>()
        req.journals?.forEach { item ->
            val key = "journal:${item.id}"
            if (item.id == null) {
                val existing = item.clientId?.let { cid -> journalItems.firstOrNull { it.userId == 1L && it.clientId == cid } }
                val target = if (existing != null) {
                    // Preserve original status on clientId duplicate-key idempotency and echo authoritative status.
                    existing
                } else {
                    JournalDto(
                        id = ++nextJournalId,
                        userId = 1L,
                        clientId = item.clientId,
                        entryDate = item.entryDate,
                        mood = JournalRules.normalizeMood(item.mood),
                        tags = JournalRules.normalizeTags(item.tags),
                        content = JournalRules.normalizeContent(item.content),
                        status = item.status,
                        version = 0L,
                    ).also { journalItems += it }
                }
                results += SyncItemResult(
                    key = key,
                    ok = true,
                    code = 0,
                    message = "ok",
                    id = target.id,
                    version = target.version,
                    clientId = target.clientId,
                    status = target.status,
                )
            } else {
                val old = journalItems.firstOrNull { it.id == item.id && it.userId == 1L }
                when {
                    old == null -> results += SyncItemResult(key = key, ok = false, code = 1003, message = "日常记录不存在", clientId = item.clientId)
                    old.version != item.baseVersion -> results += SyncItemResult(key = key, ok = false, code = 1409, message = "版本冲突", clientId = item.clientId)
                    else -> {
                        val updated = old.copy(
                            entryDate = item.entryDate,
                            mood = JournalRules.normalizeMood(item.mood),
                            tags = JournalRules.normalizeTags(item.tags),
                            content = JournalRules.normalizeContent(item.content),
                            status = item.status,
                            version = old.version + 1L,
                        )
                        journalItems.removeAll { it.id == item.id }
                        journalItems += updated
                        results += SyncItemResult(
                            key = key,
                            ok = true,
                            code = 0,
                            message = "ok",
                            id = updated.id,
                            version = updated.version,
                            clientId = updated.clientId,
                            status = updated.status,
                        )
                    }
                }
            }
        }
        return ApiResponse(0, "ok", SyncPushResponse(results))
    }

    override suspend fun journals(from: String?, to: String?, mood: String?): ApiResponse<List<JournalDto>> =
        ApiResponse(
            0,
            "ok",
            journalItems
                .filter { it.status == 1 }
                .filter { from == null || it.entryDate >= from }
                .filter { to == null || it.entryDate <= to }
                .filter { mood == null || it.mood == mood }
                .sortedWith(compareByDescending<JournalDto> { it.entryDate }.thenByDescending { it.id }),
        )

    override suspend fun createJournal(req: JournalUpsertRequest): ApiResponse<JournalDto> {
        val existing = req.clientId?.let { cid -> journalItems.firstOrNull { it.clientId == cid } }
        if (existing != null) return ApiResponse(0, "ok", existing)
        val item = JournalDto(
            id = ++nextJournalId,
            userId = 1L,
            clientId = req.clientId,
            entryDate = req.entryDate,
            mood = JournalRules.normalizeMood(req.mood),
            tags = JournalRules.normalizeTags(req.tags),
            content = JournalRules.normalizeContent(req.content),
            status = 1,
            version = 0L,
        )
        journalItems += item
        return ApiResponse(0, "ok", item)
    }

    override suspend fun updateJournal(id: Long, baseVersion: Long, req: JournalUpsertRequest): ApiResponse<JournalDto> {
        val old = journalItems.firstOrNull { it.id == id && it.status == 1 }
            ?: return ApiResponse(1003, "日常记录不存在", null)
        if (old.version != baseVersion) return ApiResponse(1409, "版本冲突", null)
        val updated = old.copy(
            entryDate = req.entryDate,
            mood = JournalRules.normalizeMood(req.mood),
            tags = JournalRules.normalizeTags(req.tags),
            content = JournalRules.normalizeContent(req.content),
            version = old.version + 1,
        )
        journalItems.removeAll { it.id == id }
        journalItems += updated
        return ApiResponse(0, "ok", updated)
    }

    override suspend fun deleteJournal(id: Long, baseVersion: Long?): ApiResponse<Unit> {
        val old = journalItems.firstOrNull { it.id == id } ?: return ApiResponse(0, "ok", Unit)
        if (old.status == 0) return ApiResponse(0, "ok", Unit)
        if (baseVersion != null && old.version != baseVersion) return ApiResponse(1409, "版本冲突", null)
        journalItems.removeAll { it.id == id }
        journalItems += old.copy(status = 0, version = old.version + 1)
        return ApiResponse(0, "ok", Unit)
    }

    override suspend fun exportJournals(format: String): ResponseBody =
        JournalRules.exportMarkdown(
            journalItems.filter { it.status == 1 }.map {
                JournalExportItem(it.id, it.entryDate, it.mood, it.tags, it.content)
            },
        ).toResponseBody()
}
