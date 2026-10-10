package com.planlock.app.api

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
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.ResponseBody

/**
 * REST 客户端契约（对应 04-技术实现/接口设计.md，开发期用 mock）。
 */
interface PlanLockApi {

    @POST("auth/device")
    suspend fun deviceRegister(@Body req: DeviceRegisterRequest): ApiResponse<AuthResponse>

    @POST("auth/code/send")
    suspend fun sendCode(@Body req: SendCodeRequest): ApiResponse<Unit>

    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): ApiResponse<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): ApiResponse<AuthResponse>

    @POST("auth/merge")
    suspend fun merge(@Body req: MergeRequest): ApiResponse<Map<String, Long>>

    @GET("devices")
    suspend fun devices(): ApiResponse<List<DeviceDto>>

    @PUT("devices/{id}/primary")
    suspend fun setPrimaryDevice(@Path("id") id: Long): ApiResponse<Unit>

    @DELETE("devices/{id}")
    suspend fun unbindDevice(@Path("id") id: Long): ApiResponse<Unit>

    @PUT("me/backup-password")
    suspend fun setBackupPassword(@Body req: BackupPasswordSetRequest): ApiResponse<Unit>

    @POST("me/backup-password/reset")
    suspend fun resetBackupPassword(@Body req: BackupPasswordResetRequest): ApiResponse<Unit>

    @GET("plans")
    suspend fun plans(): ApiResponse<List<PlanDto>>

    @POST("plans")
    suspend fun createPlan(@Body req: PlanUpsertRequest): ApiResponse<PlanDto>

    @PUT("plans/{id}")
    suspend fun updatePlan(
        @Path("id") id: Long,
        @Query("baseVersion") baseVersion: Long,
        @Body req: PlanUpsertRequest
    ): ApiResponse<PlanDto>

    @DELETE("plans/{id}")
    suspend fun deletePlan(@Path("id") id: Long): ApiResponse<Unit>

    @POST("plans/{id}/pause")
    suspend fun pausePlan(@Path("id") id: Long): ApiResponse<PlanDto>

    @GET("schedules") suspend fun schedules(): ApiResponse<List<ScheduleDto>>
    @POST("schedules") suspend fun createSchedule(@Body req: ScheduleUpsertRequest): ApiResponse<ScheduleDto>
    @PUT("schedules/{id}") suspend fun updateSchedule(@Path("id") id: Long,@Query("baseVersion") baseVersion: Long,@Body req: ScheduleUpsertRequest): ApiResponse<ScheduleDto>
    @DELETE("schedules/{id}") suspend fun deleteSchedule(@Path("id") id: Long): ApiResponse<Unit>

    @GET("plans/{id}/strategy")
    suspend fun strategy(@Path("id") planId: Long): ApiResponse<StrategyDto>

    @PUT("plans/{id}/strategy")
    suspend fun upsertStrategy(
        @Path("id") planId: Long,
        @Query("baseVersion") baseVersion: Long?,
        @Body req: StrategyUpsertRequest
    ): ApiResponse<StrategyDto>

    @GET("records/today")
    suspend fun today(): ApiResponse<TodaySummary>

    @GET("records/stats")
    suspend fun stats(@Query("period") period: String = "week"): ApiResponse<StatsResponse>

    @GET("records/export")
    suspend fun exportRecords(@Query("format") format: String): ResponseBody

    @GET("plans/export")
    suspend fun exportPlans(): ResponseBody

    @POST("records/execution")
    suspend fun reportExecution(@Body req: ExecutionRecordRequest): ApiResponse<Unit>

    @POST("records/violation")
    suspend fun reportViolation(@Body req: ViolationRecordRequest): ApiResponse<Unit>

    @POST("records/unlock")
    suspend fun reportUnlock(@Body req: UnlockRecordRequest): ApiResponse<Unit>

    @GET("sync/pull")
    suspend fun syncPull(): ApiResponse<SyncPullResponse>

    @POST("sync/push")
    suspend fun syncPush(@Body req: SyncPushRequest): ApiResponse<SyncPushResponse>
}
