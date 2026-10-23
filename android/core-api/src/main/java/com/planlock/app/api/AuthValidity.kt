package com.planlock.app.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64

enum class AuthValidity { UNKNOWN, VALID, EXPIRED_LOCAL, REJECTED }

data class AuthState(
    val session: AuthSession = AuthSession(),
    val validity: AuthValidity = AuthValidity.UNKNOWN,
    val signedOut: Boolean = false,
    val dataOwner: Long? = null,
    val instanceId: String? = null,
    val dataOwnerInstance: String? = null,
    val hasSignedInBefore: Boolean = false,
) {
    val expired get() = validity == AuthValidity.EXPIRED_LOCAL || validity == AuthValidity.REJECTED
    // Retained owner fields only support legacy migration; active data follows this session.
    val ownershipMismatch get() = false
    val localDataUserId get() = session.userId.takeUnless { signedOut }
    val dataScope get() = if (localDataUserId == null) "local\nlocal" else "${instanceId ?: "local"}\n$localDataUserId"
}

/** A UI hint only. The backend remains the authority for signature and account validation. */
fun jwtExpiredLocally(token: String?, nowEpochSeconds: Long): Boolean = try {
    val payload = token?.split('.')?.takeIf { it.size == 3 }?.get(1)
    val expiry = payload?.decodeBase64()?.utf8()
        ?.let { Json.parseToJsonElement(it).jsonObject["exp"]?.jsonPrimitive?.longOrNull }
    expiry != null && expiry <= nowEpochSeconds
} catch (_: Exception) { false }
