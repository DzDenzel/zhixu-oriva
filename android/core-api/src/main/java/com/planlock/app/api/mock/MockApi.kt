package com.planlock.app.api.mock

import com.planlock.app.api.PlanLockApi
import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.AuthResponse
import com.planlock.app.api.dto.BackupPasswordResetRequest
import com.planlock.app.api.dto.BackupPasswordSetRequest
import com.planlock.app.api.dto.DeviceDto
import com.planlock.app.api.dto.DeviceRegisterRequest
import com.planlock.app.api.dto.ExecutionRecordRequest
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
    private val scheduleItems = mutableListOf<ScheduleDto>()

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
        ApiResponse(0, "ok", SyncPullResponse())

    override suspend fun syncPush(req: SyncPushRequest): ApiResponse<SyncPushResponse> =
        ApiResponse(0, "ok", SyncPushResponse(emptyList()))
}
