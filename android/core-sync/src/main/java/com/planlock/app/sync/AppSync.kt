package com.planlock.app.sync

import android.content.Context
import android.provider.Settings
import com.planlock.app.api.*
import com.planlock.app.api.dto.*
import com.planlock.app.record.RecordStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.withLock

/** Single process entry point for UI/service intents; never retains an Activity. */
class AppSync private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = ApiClient.create()
    private val records = RecordStore(app)
    var beforePlanMapping: suspend (Long, Long) -> Unit = { _, _ -> }
    var afterGuestMerge: suspend (List<String>, String) -> Unit = { _, _ -> }
    private val client = SyncClient(api, records, owner = { TokenStore.userId }) { old, new -> beforePlanMapping(old, new) }
    val cache = SyncOverviewCache(app.planLockPreferences)
    val foregroundGate = ForegroundSyncGate()
    val coordinator = SyncCoordinator(scope) {
        android.util.Log.i("OrivaSync", "coordinated-sync-start")
        try { supervisorScope {
            val weather = async { try { refreshWeather() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
            try { sessionLock.withLock { synchronize() } } finally { weather.await() }
        } }
        finally { android.util.Log.i("OrivaSync", "coordinated-sync-end") }
    }
    private val authLock = kotlinx.coroutines.sync.Mutex()
    private val registrationGate = DeviceRegistrationGate()
    private val sessionLock = kotlinx.coroutines.sync.Mutex()
    private val localIdentityLock = kotlinx.coroutines.sync.Mutex()
    private val weatherLock = kotlinx.coroutines.sync.Mutex()
    private val mergeOffer = kotlinx.coroutines.flow.MutableStateFlow<GuestMergeOffer?>(null)
    private val mergeJournal = GuestMergeJournal(app.planLockPreferences)
    val guestMergeOffer = mergeOffer.asStateFlow()
    private val uploads = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(TokenStore.state.map { it.dataScope }.distinctUntilChanged(), records.localRevision,
                PlanTimingPreferences.enabled(app.planLockPreferences)) { _, _, _ -> Unit }
                .collect {
                    withLocalMutation {
                        try { com.planlock.app.record.PlanAlarmScheduler(app).reconcile(records) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { android.util.Log.w("OrivaAlarms", "Plan alarm reconciliation failed", error) }
                    }
                }
        }
        scope.launch {
            coordinator.state.collect { value ->
                android.util.Log.i("OrivaSync", "state=${value.phase} attempt=${value.attempts} revision=${value.revision}")
            }
        }
        scope.launch {
            TokenStore.state.map { it.dataScope }.distinctUntilChanged().collect {
                mergeOffer.value = mergeJournal.read(it)
                val reminders = com.planlock.app.record.ScheduleReminderScheduler(app)
                reminders.cancelAll()
                records.schedules().forEach { row -> reminders.schedule(row) }
            }
        }
        scope.launch {
            for (ownerScope in uploads) {
                try {
                    withTimeout(20_000) {
                        sessionLock.withLock {
                            if (ownerScope != records.currentScope() || ConnectionStore.state.value.config == null) return@withLock
                            ensureSession()
                            client.pushPendingRecords()
                        }
                    }
                    if (ownerScope == records.currentScope()) coordinator.pendingFeedback(null)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    if (ownerScope == records.currentScope()) coordinator.pendingFeedback("未同步，已保留本机数据；可下拉重试")
                } catch (_: Exception) { if (ownerScope == records.currentScope()) coordinator.pendingFeedback("未同步，已保留本机数据；可下拉重试") }
            }
        }
    }

    /** Local commits never queue behind an unrelated network transfer. */
    suspend fun <T> withLocalMutation(block: suspend () -> T): T = localIdentityLock.withLock { block() }

    suspend fun setPunctualPlanStart(enabled: Boolean) = withLocalMutation {
        PlanTimingPreferences.setEnabled(app.planLockPreferences, enabled)
        com.planlock.app.record.PlanAlarmScheduler(app).reconcile(records)
    }

    fun localChangesCommitted() {
        coordinator.pendingFeedback("未同步，已保留本机数据")
        uploads.trySend(records.currentScope())
    }

    suspend fun commitLogin(auth: AuthResponse, connection: ConnectionState): Long? = withSessionChange {
        val previous = TokenStore.state.value
        val guestId = auth.guestUserId ?: previous.session.guestUserId
        val sources = buildList {
            add("local\nlocal")
            if (!previous.session.signedIn) add(previous.dataScope)
            if (guestId != null && connection.instanceId != null) add("${connection.instanceId}\n$guestId")
        }.distinct()
        val count = records.guestContentCount(sources)
        val sourceScope = guestId?.let { "${connection.instanceId}\n$it" }
        val proof = if (!previous.session.signedIn && previous.session.userId == guestId) previous.session.token else sourceScope?.let { mergeJournal.proof(it) }
        if (sourceScope != null && proof != null) mergeJournal.saveProof(sourceScope, proof)
        val accountScope = "${connection.instanceId}\n${auth.userId}"
        val offer = GuestMergeOffer(sources, guestId, count, accountScope, proof)
        // Write before switching identity so a process death cannot hide the pending decision.
        if (count > 0 || guestId != null) mergeJournal.save(offer)
        TokenStore.save(app.planLockPreferences, AuthSession(auth.token, auth.userId, auth.deviceId, guestId, true), connection)
        if (count == 0 && guestId == null) return@withSessionChange null
        mergeOffer.value = offer
        if (!previous.hasSignedInBefore) {
            try { mergeGuest(offer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // The offer remains visible; guest data is still untouched.
        } else null
    }

    suspend fun chooseGuestMerge(merge: Boolean) = withSessionChange {
        val offer = mergeOffer.value ?: return@withSessionChange
        check(offer.accountScope == records.currentScope()) { "登录账号已变化" }
        if (merge) mergeGuest(offer) else { mergeJournal.clear(offer.accountScope); mergeOffer.value = null }
    }

    private suspend fun mergeGuest(offer: GuestMergeOffer): Long {
        val response = api.merge(MergeRequest(offer.guestId, offer.guestToken))
        check(response.code == 0 && response.data != null) { "游客合并未确认，内容已保留" }
        records.acceptGuestData(offer.sourceScopes)
        afterGuestMerge(offer.sourceScopes, records.currentScope())
        val reminders = com.planlock.app.record.ScheduleReminderScheduler(app)
        records.schedules().forEach { reminders.schedule(it) }
        mergeJournal.clear(offer.accountScope)
        mergeOffer.value = null
        return offer.count.toLong()
    }

    /** Identity commit/merge cannot overlap an automatic transfer authenticated as the old identity. */
    suspend fun <T> withSessionChange(block: suspend () -> T): T = sessionLock.withLock { localIdentityLock.withLock {
        val previousConnection = ConnectionStore.state.value
        val result = block()
        if (ConnectionStore.state.value.config == null || ConnectionStore.state.value != previousConnection) coordinator.clearError()
        result
    } }

    /** Caller holds the session lock and has checked a successful merge response. */
    suspend fun acceptMergedHistory(guestId: Long) {
        check(TokenStore.canSync)
        records.claimMergedHistory(checkNotNull(ConnectionStore.state.value.instanceId),guestId)
    }

    private suspend fun ensureSession() {
        authLock.lock()
        try {
            TokenStore.restore(app.planLockPreferences)
            if (!TokenStore.hasSession) {
                requireAutomaticRegistration(TokenStore.canRegisterDevice, TokenStore.state.value)
                val uid = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-dev"
                val auth = registrationGate.register(ConnectionStore.state.value) {
                    api.deviceRegister(DeviceRegisterRequest(uid, "Android", "android"))
                }
                localIdentityLock.withLock {
                    TokenStore.save(app.planLockPreferences, AuthSession(auth.token, auth.userId, auth.deviceId, auth.userId))
                    records.acceptGuestData(listOf("local\nlocal"))
                }
            }
            check(TokenStore.canSync) { "本机数据属于原账号，自动同步已暂停，请登录原账号" }
            check(TokenStore.state.value.validity != AuthValidity.REJECTED) { "登录已过期，请重新登录" }
            if (!TokenStore.signedIn) localIdentityLock.withLock { records.acceptGuestData(listOf("local\nlocal")) }
        } finally { authLock.unlock() }
    }
    private suspend fun synchronize() {
        if (ConnectionStore.state.value.config == null) return
        ensureSession()
        val owner = TokenStore.userId
        var overview = try { cache.read(owner) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { SyncOverview() } // A corrupt cache must be repairable by a successful refresh.
        val failures = mutableListOf<Exception>()
        suspend fun part(block: suspend () -> Unit) {
            if (TokenStore.state.value.validity == AuthValidity.REJECTED) return
            try { block() }
            catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); failures += e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += e }
        }
        part { client.pushPendingRecords() }
        part { withTimeout(10_000) { client.pull() } }
        part { withTimeout(30_000) { client.pullHistory() } }
        part { val r = withTimeout(10_000) { api.today() }; check(r.code == 0); overview = overview.copy(today = checkNotNull(r.data)) }
        part {
            val reports = fetchReportScopes { period -> withTimeout(10_000) { api.stats(period) } }
            overview = overview.withReportScopes(reports, System.currentTimeMillis())
        }
        part { val r = withTimeout(10_000) { api.devices() }; check(r.code == 0); val devices = checkNotNull(r.data); overview = overview.copy(deviceCount = devices.size, devices = devices) }
        // Never assign a response received across an identity change to the new account.
        if (owner == TokenStore.userId) cache.save(owner, overview)
        if (failures.isNotEmpty()) throw failures.first()
    }
    fun weatherLocationChanged(): Job = scope.launch {
        try { withTimeout(20_000) { refreshWeather() } }
        catch (cancelled: CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
        catch (_: Exception) { }
    }
    private suspend fun refreshWeather(): Unit = weatherLock.withLock {
        val weatherStore = WeatherStore(app.planLockPreferences)
        val manual = weatherStore.location()
        val location = manual ?: SystemLocationProvider.get(app).resolve()
        if (location != null) WeatherClient().current(location)?.let { snapshot ->
            if (weatherStore.location() == manual && (manual != null || app.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)) weatherStore.saveSnapshot(snapshot)
        }
    }
    /** Mutations and service telemetry preserve reliable pending uploads without a full pull. */
    suspend fun pushPendingRecords() = withTimeout(20_000) { sessionLock.withLock {
        if (ConnectionStore.state.value.config == null) return@withLock
        if (records.pending().isEmpty()) return@withLock
        ensureSession()
        client.pushPendingRecords()
    } }

    /** A choice is a manual sync intent, serialized with connection/account changes. */
    suspend fun resolveConfirmation(expected: com.planlock.app.record.SyncConfirmation, keepMine: Boolean) {
        sessionLock.withLock {
            ensureSession()
            client.pull() // Re-read before applying the precise versions displayed in the dialog.
            records.resolveConfirmation(expected, keepMine)
        }
        coordinator.refresh()
    }
    suspend fun approveBulkDeletion(expected: com.planlock.app.record.BulkDeletion) {
        sessionLock.withLock {
            ensureSession()
            records.approveBulkDeletion(expected)
        }
        coordinator.refresh()
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
