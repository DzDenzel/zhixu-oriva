package com.planlock.app.api.dto

import kotlinx.serialization.Serializable

/** 统一响应体（对应后端 ApiResponse）。 */
@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "ok",
    val data: T? = null
)

@Serializable
data class DeviceRegisterRequest(
    val deviceUid: String,
    val deviceName: String? = null,
    val platform: String
)

@Serializable
data class RegisterRequest(
    val account: String,
    val code: String,
    val password: String? = null,
    val deviceUid: String,
    val deviceName: String? = null,
    val platform: String
)

@Serializable
data class LoginRequest(
    val account: String,
    val password: String? = null,
    val code: String? = null,
    val deviceUid: String,
    val deviceName: String? = null,
    val platform: String
)

@Serializable
data class SendCodeRequest(
    val account: String,
    val scene: String
)

@Serializable
data class MergeRequest(val guestUserId: Long? = null)

@Serializable
data class AuthResponse(
    val token: String? = null,
    val userId: Long? = null,
    val deviceId: Long? = null,
    val guestUserId: Long? = null
)

@Serializable
data class DeviceDto(
    val id: Long = 0,
    val userId: Long = 0,
    val deviceName: String? = null,
    val platform: String? = null,
    val deviceUid: String? = null,
    val isPrimary: Boolean? = null,
    val lastOnlineAt: String? = null,
    val online: Boolean? = null
)

@Serializable
data class BackupPasswordSetRequest(
    val backupPassword: String,
    val securityQuestion: String? = null,
    val securityAnswer: String? = null
)

@Serializable
data class BackupPasswordResetRequest(
    val account: String,
    val code: String? = null,
    val securityAnswer: String? = null,
    val newBackupPassword: String
)

@Serializable
data class RepeatRule(
    val type: String? = null,
    val weekdays: List<Int>? = null,
    val dates: List<String>? = null
)

@Serializable
data class RemindMethods(
    val notification: Boolean = true,
    val sound: Boolean = false,
    val multiDevice: Boolean = false,
    val repeatMultiple: Boolean = false
)

@Serializable
data class PlanUpsertRequest(
    val name: String,
    val type: String,
    val startTime: String,
    val endTime: String,
    val repeatRule: RepeatRule? = null,
    val color: String? = null,
    val icon: String? = null,
    val remindAdvanceMin: Int? = 15,
    val remindMethods: RemindMethods? = null,
    val clientId: String? = null,
)

@Serializable
data class PlanDto(
    val id: Long = 0,
    val userId: Long = 0,
    val name: String = "",
    val type: String = "",
    val startTime: String? = null,
    val endTime: String? = null,
    val repeatRule: RepeatRule? = null,
    val color: String? = null,
    val icon: String? = null,
    val remindAdvanceMin: Int? = null,
    val remindMethods: RemindMethods? = null,
    val status: Int? = null,
    val version: Long? = null,
    val clientId: String? = null,
)

@Serializable
data class ScheduleUpsertRequest(
    val title: String,
    val startDate: String,
    val startTime: String? = null,
    val endDate: String,
    val endTime: String? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val note: String? = null,
    val remindAdvanceMin: Int? = null,
    val repeatRule: String? = null,
    val linkedPlanId: Long? = null,
    val tzId: String? = null,
    val clientId: String? = null,
)

@Serializable
data class ScheduleDto(
    val id: Long = 0,
    val userId: Long = 0,
    val title: String = "",
    val startDate: String = "",
    val startTime: String? = null,
    val endDate: String = "",
    val endTime: String? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val note: String? = null,
    val remindAdvanceMin: Int? = null,
    val repeatRule: String? = null,
    val linkedPlanId: Long? = null,
    val tzId: String? = null,
    val status: Int = 1,
    val version: Long = 0,
    val clientId: String? = null,
)

@Serializable
data class AppItem(val name: String? = null, val packageName: String? = null)

@Serializable
data class StrategyUpsertRequest(
    val blacklistApps: List<AppItem>? = null,
    val whitelistApps: List<AppItem>? = null,
    val autoDnd: Boolean? = null,
    val notifySilence: Boolean? = null
)

@Serializable
data class StrategyDto(
    val id: Long = 0,
    val planId: Long = 0,
    val blacklistApps: List<AppItem>? = null,
    val whitelistApps: List<AppItem>? = null,
    val autoDnd: Boolean? = null,
    val notifySilence: Boolean? = null,
    val version: Long? = null
)

@Serializable
data class ExecutionRecordRequest(
    val planId: Long,
    val planDate: String,
    val status: Int,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val clientId: String
)

@Serializable
data class ViolationRecordRequest(
    val planId: Long,
    val recordAt: String,
    val appName: String? = null,
    val packageName: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMs: Long? = null,
    val sourceLevel: String? = null,
    val clientId: String
)

@Serializable
data class UnlockRecordRequest(
    val planId: Long,
    val unlockAt: String,
    val method: String = "backup_password",
    val clientId: String
)

@Serializable
data class ExecutionRecordDto(
    val id: Long = 0,
    val planId: Long = 0,
    val deviceId: Long = 0,
    val planDate: String? = null,
    val status: Int? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val clientId: String? = null
)

@Serializable
data class TodaySummary(
    val date: String? = null,
    val executions: List<ExecutionRecordDto>? = null,
    val violationCount: Int = 0,
    val unlockCount: Int = 0
)

@Serializable
data class DailyStat(val date: String? = null, val completed: Int = 0)

@Serializable
data class ViolationAppStat(
    val packageName: String = "",
    val appName: String? = null,
    val count: Int = 0,
    val totalDurationMs: Long = 0,
    val approxDurationMs: Long = 0,
)

@Serializable
data class PlanExecutionStat(
    val planId: Long = 0,
    val completedCount: Int = 0,
    val earlyEndedCount: Int = 0,
    val actualDurationMs: Long = 0,
)

@Serializable
data class EarlyEnding(
    val at: String? = null,
    val planId: Long = 0,
    val method: String? = null,
)

@Serializable
data class StatsResponse(
    val period: String? = null,
    val continuousDays: Int = 0,
    val completedCount: Int = 0,
    val violationCount: Int = 0,
    val violationDurationMs: Long = 0,
    val violationByApp: List<ViolationAppStat> = emptyList(),
    val planExecution: List<PlanExecutionStat> = emptyList(),
    val earlyEndings: List<EarlyEnding> = emptyList(),
    val legacyViolationCount: Int = 0,
    val unlockCount: Int = 0,
    val daily: List<DailyStat>? = null
)

@Serializable
data class SyncPlanView(
    val id: Long = 0,
    val userId: Long = 0,
    val name: String = "",
    val type: String = "",
    val startTime: String? = null,
    val endTime: String? = null,
    val repeatRule: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val remindAdvanceMin: Int? = null,
    val remindMethods: String? = null,
    val status: Int? = null,
    val version: Long? = null,
    val clientId: String? = null,
)

@Serializable
data class SyncStrategyView(
    val id: Long = 0,
    val planId: Long = 0,
    val blacklistApps: String? = null,
    val whitelistApps: String? = null,
    val autoDnd: Boolean? = null,
    val notifySilence: Boolean? = null,
    val version: Long? = null
)

@Serializable
data class SyncScheduleView(
    val id: Long = 0, val userId: Long = 0, val title: String = "",
    val startDate: String = "", val startTime: String? = null,
    val endDate: String = "", val endTime: String? = null,
    val allDay: Boolean = false, val location: String? = null, val note: String? = null,
    val remindAdvanceMin: Int? = null, val repeatRule: String? = null,
    val linkedPlanId: Long? = null, val tzId: String? = null,
    val status: Int = 1, val version: Long = 0,
    val clientId: String? = null,
)

@Serializable
data class SyncPullResponse(
    val plans: List<SyncPlanView>? = null,
    val schedules: List<SyncScheduleView>? = null,
    val strategies: List<SyncStrategyView>? = null,
    val recordCounts: Map<String, Int>? = null
)

@Serializable
data class SyncPlanItem(
    val id: Long? = null,
    val baseVersion: Long? = null,
    val name: String = "",
    val type: String = "",
    val startTime: String? = null,
    val endTime: String? = null,
    val repeatRule: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val remindAdvanceMin: Int? = null,
    val remindMethods: String? = null,
    val status: Int? = null,
    val clientId: String? = null,
)

@Serializable
data class SyncStrategyItem(
    val planId: Long = 0,
    val baseVersion: Long? = null,
    val blacklistApps: String? = null,
    val whitelistApps: String? = null,
    val autoDnd: Boolean? = null,
    val notifySilence: Boolean? = null
)

@Serializable
data class SyncScheduleItem(
    val id: Long? = null, val baseVersion: Long? = null, val title: String,
    val startDate: String, val startTime: String? = null,
    val endDate: String, val endTime: String? = null,
    val allDay: Boolean = false, val location: String? = null, val note: String? = null,
    val remindAdvanceMin: Int? = null, val repeatRule: String? = null,
    val linkedPlanId: Long? = null, val tzId: String? = null, val status: Int = 1,
    val clientId: String? = null,
)

@Serializable
data class SyncPushRequest(
    val plans: List<SyncPlanItem>? = null,
    val strategies: List<SyncStrategyItem>? = null,
    val schedules: List<SyncScheduleItem>? = null,
)

@Serializable
data class SyncItemResult(
    val key: String = "",
    val ok: Boolean = false,
    val code: Int = 0,
    val message: String = "",
    val id: Long? = null,
    val version: Long? = null,
    val clientId: String? = null
)

@Serializable
data class SyncPushResponse(val results: List<SyncItemResult>? = null)
