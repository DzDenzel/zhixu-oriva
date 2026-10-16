package com.planlock.app.api

import com.planlock.core.api.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Public connection document only. Never put authentication or deployment credentials here. */
@Serializable
data class ConnectionConfig(
    val version: Int = 1,
    val instanceLabel: String,
    val apiBaseUrl: String,
    val pluginsCatalogUrl: String,
    val lanAllowed: Boolean = false,
) {
    val instanceId: String get() = apiBaseUrl
    fun catalogUrl(source: String): String {
        require(source == "official" || source == "third-party")
        return pluginsCatalogUrl + source
    }
    fun encode(): String = codec.encodeToString(this)
    fun validated(): ConnectionConfig = decode(encode())

    companion object {
        private val codec = Json { encodeDefaults = true }
        val default: ConnectionConfig get() = ConnectionConfig(
            instanceLabel = "本机开发后端",
            apiBaseUrl = BuildConfig.API_BASE_URL,
            pluginsCatalogUrl = BuildConfig.API_BASE_URL + "plugins/catalog/",
            lanAllowed = true,
        ).validated()
        private val fields = setOf("version", "instanceLabel", "apiBaseUrl", "pluginsCatalogUrl", "lanAllowed")

        fun decode(raw: String): ConnectionConfig {
            // A generic error deliberately never reflects user input or parser diagnostics.
            try {
                require(raw.length <= 16_384)
                val doc = Json.parseToJsonElement(raw).jsonObject
                require(doc.keys == fields)
                val v = doc.getValue("version").jsonPrimitive
                val lan = doc.getValue("lanAllowed").jsonPrimitive
                require(!v.isString && v.content == "1" && !lan.isString)
                val lanAllowed = requireNotNull(lan.booleanOrNull)
                fun string(key: String): String = doc.getValue(key).jsonPrimitive.also { require(it.isString) }.content
                val label = string("instanceLabel").trim()
                require(label.length in 1..80 && label.none { it.isISOControl() })
                fun endpoint(key: String): String {
                    val text = string(key)
                    require(text.length <= 2_048 && text.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
                    val uri = java.net.URI(text)
                    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                    val url = requireNotNull(text.toHttpUrlOrNull())
                    require(url.username.isEmpty() && url.password.isEmpty())
                    require(url.scheme == "https" || lanAllowed && isLanHost(url.host))
                    require(!isLanHost(url.host) || lanAllowed)
                    return url.newBuilder().apply {
                        if (!url.encodedPath.endsWith('/')) addPathSegment("")
                    }.build().toString()
                }
                return ConnectionConfig(1, label, endpoint("apiBaseUrl"), endpoint("pluginsCatalogUrl"), lanAllowed)
            } catch (_: Exception) {
                throw IllegalArgumentException("连接配置无效：请检查版本、字段与地址；配置不得包含密码或密钥")
            }
        }
    }
}

/** Never resolve arbitrary hostnames during document parsing. DNS is checked again by LAN transport. */
internal fun isLanHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || (!host.contains('.') && !host.contains(':'))) return true
    if (host.contains(':') || host.matches(Regex("[0-9.]+"))) return try {
        isLanAddress(InetAddress.getByName(host))
    } catch (_: Exception) { false }
    return false
}

internal fun isLanAddress(address: InetAddress): Boolean =
    address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
