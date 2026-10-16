package com.planlock.app.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PlanLockPreferenceKeys {
    val basicToolsEnabled = booleanPreferencesKey("basic_tools_enabled")
    val pluginsEnabled = booleanPreferencesKey("plugins_enabled")
    val actionGateEnabled = booleanPreferencesKey("action_gate_enabled")
    val cachedContinuousDays = intPreferencesKey("cached_continuous_days")
    val cachedTodayUnlocks = intPreferencesKey("cached_today_unlocks")
    val dndPreviousFilter = intPreferencesKey("dnd_previous_filter")
    val dndPlanId = longPreferencesKey("dnd_plan_id")
    val dndAppliedAt = longPreferencesKey("dnd_applied_at")
    val permissionGuideDismissedAt = longPreferencesKey("perm_guide_dismissed_at")
    val notificationPermissionRequested = booleanPreferencesKey("notification_permission_requested")
    val locationPermissionRequested = booleanPreferencesKey("location_permission_requested")
}

// 全进程共用一份实例，主题迁移和身份更新不会相互覆盖。
val Context.planLockPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "planlock",
    produceMigrations = { listOf(SharedPreferencesMigration(it, "appearance", setOf("theme_mode"))) }
)

data class AuthSession(
    val token: String? = null,
    val userId: Long? = null,
    val deviceId: Long? = null,
    val guestUserId: Long? = null,
    val signedIn: Boolean = false
) {
    val usable: Boolean get() = !token.isNullOrBlank() && userId != null && deviceId != null
}

object TokenStore {
    private val lock = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(AuthState())
    val state = mutableState.asStateFlow()
    private val session get() = state.value.session
    val token get() = session.token
    val userId get() = session.userId
    val deviceId get() = session.deviceId
    val guestUserId get() = session.guestUserId
    val signedIn get() = session.signedIn
    val hasSession get() = session.usable
    val canRegisterDevice get() = ConnectionStore.state.value.config != null && !state.value.signedOut && !state.value.ownershipMismatch
    val canSync get() = ConnectionStore.state.value.config != null && hasSession && !state.value.signedOut && !state.value.ownershipMismatch
    val localDataUserId get() = state.value.localDataUserId

    private val tokenKey = stringPreferencesKey("token")
    private val userKey = longPreferencesKey("user_id")
    private val deviceKey = longPreferencesKey("device_id")
    private val guestKey = longPreferencesKey("guest_user_id")
    private val signedInKey = booleanPreferencesKey("signed_in")
    private val signedOutKey = booleanPreferencesKey("auth_signed_out")
    private val dataOwnerKey = longPreferencesKey("auth_data_owner")
    private val dataOwnerInstanceKey = stringPreferencesKey("auth_data_owner_instance")

    suspend fun restore(store: DataStore<Preferences>): AuthValidity = lock.withLock {
        ConnectionStore.restore(store)
        val p = store.data.first()
        val saved = AuthSession(p[tokenKey], p[userKey], p[deviceKey], p[guestKey], p[signedInKey] ?: false)
        synchronized(stateLock) {
            val previous = state.value
            val restored = if (saved.usable) saved else AuthSession(guestUserId = saved.guestUserId)
            val validity = when {
                previous.session.token == restored.token && previous.validity == AuthValidity.REJECTED -> AuthValidity.REJECTED
                jwtExpiredLocally(restored.token, System.currentTimeMillis() / 1000) -> AuthValidity.EXPIRED_LOCAL
                previous.session.token == restored.token -> previous.validity
                else -> AuthValidity.UNKNOWN
            }
            mutableState.value = AuthState(restored, validity, p[signedOutKey] ?: false,
                p[dataOwnerKey] ?: saved.userId,
                instanceId = ConnectionStore.state.value.instanceId,
                dataOwnerInstance = p[dataOwnerInstanceKey] ?: saved.userId?.let { ConnectionConfig.default.instanceId })
            validity
        }
    }

    suspend fun save(store: DataStore<Preferences>, value: AuthSession,
        expectedConnection: ConnectionState = ConnectionStore.state.value) = lock.withLock {
        require(value.usable) { "不完整的登录身份" }
        check(expectedConnection == ConnectionStore.state.value && expectedConnection.config != null) { "连接已改变，请重新登录" }
        val instance = checkNotNull(expectedConnection.instanceId)
        val committed = store.edit { p ->
            val oldInstance = p[dataOwnerInstanceKey] ?: p[userKey]?.let { ConnectionConfig.default.instanceId }
            check(oldInstance == null || oldInstance == instance) { "本机数据属于原实例，请连接原实例恢复同步" }
            val retainedOwner = p[dataOwnerKey] ?: p[userKey]
            val owner = if (value.signedIn && value.guestUserId != null && retainedOwner == value.guestUserId) {
                value.userId // Existing backend-confirmed guest merge, within this instance only.
            } else retainedOwner ?: value.userId
            p[tokenKey] = checkNotNull(value.token)
            p[userKey] = checkNotNull(value.userId)
            p[deviceKey] = checkNotNull(value.deviceId)
            value.guestUserId?.let { p[guestKey] = it } ?: p.remove(guestKey)
            p[signedInKey] = value.signedIn
            p[signedOutKey] = false
            owner?.let { p[dataOwnerKey] = it }
            p[dataOwnerInstanceKey] = oldInstance ?: instance
        }
        // 成功落盘后一次性发布，避免请求看到半份身份或虚假的登录成功。
        synchronized(stateLock) {
            mutableState.value = AuthState(value,
                if (jwtExpiredLocally(value.token, System.currentTimeMillis() / 1000)) AuthValidity.EXPIRED_LOCAL else AuthValidity.UNKNOWN,
                dataOwner = committed[dataOwnerKey], instanceId = instance, dataOwnerInstance = committed[dataOwnerInstanceKey])
        }
    }

    /** Remove credentials only; keep local data and its account boundary through cold starts. */
    suspend fun signOut(store: DataStore<Preferences>) = lock.withLock {
        val committed = store.edit { p ->
            val owner = p[dataOwnerKey] ?: p[userKey].takeIf { p[signedInKey] == true }
            owner?.let { p[dataOwnerKey] = it }
            state.value.dataOwnerInstance?.let { p[dataOwnerInstanceKey] = it }
            p.remove(tokenKey); p.remove(userKey); p.remove(deviceKey); p.remove(signedInKey)
            p[signedOutKey] = true
        }
        synchronized(stateLock) {
            mutableState.value = AuthState(AuthSession(guestUserId = committed[guestKey]),
                signedOut = true, dataOwner = committed[dataOwnerKey], instanceId = ConnectionStore.state.value.instanceId,
                dataOwnerInstance = committed[dataOwnerInstanceKey] ?: state.value.dataOwnerInstance)
        }
    }

    /** Caller also holds AppSync's session lock, so no old-instance transfer can overlap this commit. */
    suspend fun changeConnection(store: DataStore<Preferences>, next: ConnectionConfig?) = lock.withLock {
        val config = next?.validated()
        val previous = ConnectionStore.state.value
        val switched = previous.instanceId != config?.instanceId
        val committed = store.edit { p ->
            if (switched) {
                val owner = p[dataOwnerKey] ?: p[userKey]
                val mustLogin = p[signedOutKey] == true || p[signedInKey] == true
                owner?.let { p[dataOwnerKey] = it }
                if (owner != null && p[dataOwnerInstanceKey] == null) {
                    (state.value.dataOwnerInstance ?: previous.instanceId)?.let { p[dataOwnerInstanceKey] = it }
                }
                p.remove(tokenKey); p.remove(userKey); p.remove(deviceKey); p.remove(guestKey); p.remove(signedInKey)
                p[signedOutKey] = mustLogin
            }
            p[ConnectionStore.localOnlyKey] = config == null
            config?.let { p[ConnectionStore.configKey] = it.encode() } ?: p.remove(ConnectionStore.configKey)
        }
        synchronized(stateLock) {
            if (switched) mutableState.value = AuthState(signedOut = committed[signedOutKey] ?: false, dataOwner = committed[dataOwnerKey],
                instanceId = config?.instanceId, dataOwnerInstance = committed[dataOwnerInstanceKey])
            ConnectionStore.publish(config)
        }
    }

    fun observeResponse(requestToken: String?, httpStatus: Int, businessCode: Int?) = synchronized(stateLock) {
        val current = state.value
        if (requestToken.isNullOrBlank() || requestToken != current.session.token) return@synchronized
        val validity = when {
            httpStatus == 401 || businessCode == 1001 -> AuthValidity.REJECTED
            httpStatus in 200..299 && businessCode == 0 -> AuthValidity.VALID
            else -> return@synchronized
        }
        mutableState.value = current.copy(validity = validity)
    }

    fun refreshLocalValidity(nowEpochSeconds: Long = System.currentTimeMillis() / 1000) = synchronized(stateLock) {
        val current = state.value
        if (current.validity != AuthValidity.REJECTED && jwtExpiredLocally(current.session.token, nowEpochSeconds)) {
            mutableState.value = current.copy(validity = AuthValidity.EXPIRED_LOCAL)
        }
    }
}
