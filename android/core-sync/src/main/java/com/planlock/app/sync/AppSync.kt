package com.planlock.app.sync

import android.content.Context
import android.provider.Settings
import com.planlock.app.api.*
import com.planlock.app.api.dto.*
import com.planlock.app.record.RecordStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Single process entry point for UI/service intents; never retains an Activity. */
class AppSync private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ApiClient.create()
    private val records = RecordStore(app)
    var beforePlanMapping: suspend (Long, Long) -> Unit = { _, _ -> }
    private val client = SyncClient(api, records) { old, new -> beforePlanMapping(old, new) }
    val cache = SyncOverviewCache(app.planLockPreferences)
    val foregroundGate = ForegroundSyncGate()
    val coordinator = SyncCoordinator(scope) {
        android.util.Log.i("OrivaSync", "coordinated-sync-start")
        try { sessionLock.withLock { synchronize() } }
        finally { android.util.Log.i("OrivaSync", "coordinated-sync-end") }
    }
    private val authLock = kotlinx.coroutines.sync.Mutex()
    private val sessionLock = kotlinx.coroutines.sync.Mutex()

    /** Identity commit/merge cannot overlap an automatic transfer authenticated as the old identity. */
    suspend fun <T> withSessionChange(block: suspend () -> T): T = sessionLock.withLock { block() }

    private suspend fun ensureSession() {
        authLock.lock()
        try {
            TokenStore.restore(app.planLockPreferences)
            if (!TokenStore.hasSession) {
                check(TokenStore.canRegisterDevice) { "已退出登录，本地数据保留，请重新登录" }
                val uid = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-dev"
                val response = api.deviceRegister(DeviceRegisterRequest(uid, "Android", "android"))
                check(response.code == 0)
                val auth = checkNotNull(response.data)
                TokenStore.save(app.planLockPreferences, AuthSession(auth.token, auth.userId, auth.deviceId, auth.guestUserId))
            }
            check(TokenStore.canSync) { "本机数据属于原账号，自动同步已暂停，请登录原账号" }
            check(TokenStore.state.value.validity != AuthValidity.REJECTED) { "登录已过期，请重新登录" }
        } finally { authLock.unlock() }
    }
    private suspend fun synchronize() {
        ensureSession()
        val owner = TokenStore.userId
        var overview = try { cache.read(owner) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { SyncOverview() } // A corrupt cache must be repairable by a successful refresh.
        val failures = mutableListOf<Exception>()
        suspend fun part(block: suspend () -> Unit) {
            if (TokenStore.state.value.validity == AuthValidity.REJECTED) return
            try { block() }
            catch (e: TimeoutCancellationException) { failures += e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += e }
        }
        part { client.pushPendingRecords() }
        part { withTimeout(10_000) { client.pull() } }
        part { val r = withTimeout(10_000) { api.today() }; check(r.code == 0); overview = overview.copy(today = checkNotNull(r.data)) }
        part {
            val reports = fetchReportScopes { period -> withTimeout(10_000) { api.stats(period) } }
            overview = overview.withReportScopes(reports, System.currentTimeMillis())
        }
        part { val r = withTimeout(10_000) { api.devices() }; check(r.code == 0); val devices = checkNotNull(r.data); overview = overview.copy(deviceCount = devices.size, devices = devices) }
        // Never assign a response received across an identity change to the new account.
        if (owner == TokenStore.userId) cache.save(owner, overview)
        part {
            val weatherStore = WeatherStore(app.planLockPreferences)
            val location = WeatherLocationChoice.select(DeviceWeatherLocation(app).resolve(), weatherStore.location())
            if (location != null) WeatherClient().current(location)?.let { weatherStore.saveSnapshot(it) }
        }
        if (failures.isNotEmpty()) throw failures.first()
    }
    /** Mutations and service telemetry preserve reliable pending uploads without a full pull. */
    suspend fun pushPendingRecords() = sessionLock.withLock {
        if (records.pending().isEmpty()) return
        ensureSession()
        client.pushPendingRecords()
    }

    companion object {
        @Volatile private var instance: AppSync? = null
        fun get(context: Context): AppSync = instance ?: synchronized(this) {
            instance ?: AppSync(context.applicationContext).also { instance = it }
        }
    }
}

internal suspend fun fetchReportScopes(
    request: suspend (String) -> ApiResponse<StatsResponse>,
): Map<String, StatsResponse> = buildMap {
    listOf("day", "week", "month").forEach { period ->
        val response = request(period)
        check(response.code == 0)
        val value = checkNotNull(response.data)
        check(value.period == period) { "Report period mismatch: requested=$period actual=${value.period}" }
        put(period, value)
    }
}
