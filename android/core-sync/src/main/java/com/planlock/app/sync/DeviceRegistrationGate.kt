package com.planlock.app.sync

import com.planlock.app.api.ConnectionState
import com.planlock.app.api.AuthState
import com.planlock.app.api.dto.ApiResponse

class OwnerLoginRequiredException : Exception("请使用账号密码登录自建实例")

/** Explicit sign-out must continue to forbid guest registration, but is not a network error. */
internal fun requireAutomaticRegistration(allowed: Boolean, auth: AuthState) {
    if (!allowed && auth.signedOut && !auth.ownershipMismatch) throw OwnerLoginRequiredException()
    check(allowed) { "已退出登录，本地数据保留，请重新登录" }
}

/** A deployment's explicit refusal is stable for this connection, not a transfer failure. */
internal class DeviceRegistrationGate {
    private var refusedConnection: ConnectionState? = null

    suspend fun <T : Any> register(connection: ConnectionState, request: suspend () -> ApiResponse<T>): T {
        if (refusedConnection == connection) throw OwnerLoginRequiredException()
        val response = request()
        if (response.code == 1001 && response.message == "请使用账号密码登录自建实例") {
            refusedConnection = connection
            throw OwnerLoginRequiredException()
        }
        check(response.code == 0) { response.message }
        return checkNotNull(response.data)
    }
}
