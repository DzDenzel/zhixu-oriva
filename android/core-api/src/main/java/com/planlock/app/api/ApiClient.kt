package com.planlock.app.api

import com.planlock.app.api.mock.MockApi
import com.planlock.core.api.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * REST 客户端工厂。Debug 可选 mock；Release 构建固定为真实接口。
 */
object ApiClient {

    private val BASE_URL: String = BuildConfig.API_BASE_URL

    /** Public origin for non-Retrofit platform resources such as signed plugin catalogs. */
    val baseUrl: String get() = BASE_URL

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun create(useMock: Boolean = BuildConfig.USE_MOCK): PlanLockApi {
        if (useMock) {
            return MockApi()
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = authorizedRequest(chain.request())
                chain.proceed(request).also { observeAuthResponse(request, it) }
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlanLockApi::class.java)
    }
}

internal fun authorizedRequest(request: Request): Request {
    if (TokenStore.state.value.ownershipMismatch && !publicAuthRequest(request)) {
        throw java.io.IOException("本机数据属于原账号，网络写入已暂停，请登录原账号")
    }
    val token = TokenStore.token
    return if (token.isNullOrBlank()) request else request.newBuilder().header("Authorization", "Bearer $token").build()
}

/** Observe the existing exchange; never retry or consume the response body. */
internal fun observeAuthResponse(request: Request, response: Response) {
    if (publicAuthRequest(request)) return
    val requestToken = request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    if (requestToken.isNullOrBlank()) return
    val code = try {
        Json.parseToJsonElement(response.peekBody(65_536).string()).jsonObject["code"]?.jsonPrimitive?.intOrNull
    } catch (_: Exception) { null }
    TokenStore.observeResponse(requestToken, response.code, code)
}

private fun publicAuthRequest(request: Request): Boolean {
    val path = request.url.encodedPath
    return listOf("auth/device", "auth/login", "auth/register", "auth/code/send", "me/backup-password/reset")
        .any { path.endsWith("/$it") } || path.startsWith("/api/v1/plugins/")
}
