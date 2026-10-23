package com.planlock.app.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthResponseTest {
    @Test fun protectedResponsesRejectButPublicLoginFailureAndStaleResponsesDoNot() = runBlocking {
        val data = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        importTestConnection(store)
        TokenStore.save(store, AuthSession("response-test-token", 42, 8, signedIn = true))
        fun response(path: String, status: Int, body: String, token: String = "response-test-token") {
            val request = Request.Builder().url("https://example.invalid/api/v1/$path")
                .header("Authorization", "Bearer $token").build()
            val result = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("test").body(body.toResponseBody()).build()
            result.use {
                observeAuthResponse(request, it)
                assertEquals(body, it.body!!.string()) // Observation must not consume the Retrofit body.
            }
        }
        response("records/today", 200, "{\"code\":1001}")
        assertEquals(AuthValidity.REJECTED, TokenStore.state.value.validity)
        response("auth/login", 200, "{\"code\":0}")
        assertEquals(AuthValidity.REJECTED, TokenStore.state.value.validity)
        response("records/today", 200, "{\"code\":0}")
        assertEquals(AuthValidity.VALID, TokenStore.state.value.validity)
        response("auth/login", 401, "{\"code\":1001}")
        response("auth/device", 401, "{\"code\":1001}")
        response("me/backup-password/reset", 401, "{\"code\":1001}")
        response("records/today", 401, "", "old-token")
        response("records/today", 503, "unavailable")
        assertEquals(AuthValidity.VALID, TokenStore.state.value.validity)
        response("records/today", 401, "")
        assertEquals(AuthValidity.REJECTED, TokenStore.state.value.validity)
        TokenStore.save(store, AuthSession("account-b", 99, 9, signedIn = true))
        assertEquals("Bearer account-b", authorizedRequest(Request.Builder().url("https://example.invalid/api/v1/plans").build()).header("Authorization"))
        assertEquals(99L, TokenStore.localDataUserId)
        assertEquals("Bearer account-b", authorizedRequest(Request.Builder().url("https://example.invalid/api/v1/auth/login").build()).header("Authorization"))
    }
}
