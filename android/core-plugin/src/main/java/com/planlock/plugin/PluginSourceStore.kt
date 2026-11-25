package com.planlock.plugin

import android.content.Context
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CustomPluginSource(
    val id: String,
    val name: String,
    val catalogUrl: String,
    val lanAllowed: Boolean = false,
    val trustedFingerprints: Set<String> = emptySet(),
)

/** Validates third-party source URLs, trust anchor isolation, and safe-mode download gates. */
object PluginSourcePolicy {
    val reservedSourceIds: Set<String> = setOf("official", "third-party")
    private val idPattern = Regex("[A-Za-z0-9._-]+")
    private val hex64Pattern = Regex("[0-9A-F]{64}")

    fun isReservedSourceId(sourceId: String): Boolean = sourceId.trim().lowercase(Locale.ROOT) in reservedSourceIds

    fun validateSourceId(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.length in 1..64 && idPattern.matches(trimmed) && trimmed != "." && trimmed != "..") {
            "来源 ID 仅支持字母、数字、点、下划线与连字符"
        }
        require(!isReservedSourceId(trimmed)) { "不得使用内置来源 ID：$trimmed" }
        return trimmed
    }

    fun deriveSourceId(catalogUrl: String, existingIds: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(catalogUrl.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val base = "custom-${digest.take(8)}"
        if (base !in existingIds && !isReservedSourceId(base)) return base
        var suffix = 2
        while ("$base-$suffix" in existingIds) suffix++
        return "$base-$suffix"
    }

    fun validateCatalogUrl(raw: String, lanAllowed: Boolean): String {
        val trimmed = raw.trim()
        require(trimmed.length in 1..2048 && trimmed.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            "目录地址格式无效"
        }
        val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("目录地址格式无效") }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "目录地址不得包含凭据、查询参数或片段"
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "目录地址仅支持 HTTPS（或受控内网 HTTP）" }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("目录地址缺少主机名")
        val lanHost = isLanHost(host)
        require(scheme == "https" || (lanAllowed && lanHost)) { "公网第三方目录仅支持 HTTPS" }
        require(!lanHost || lanAllowed) { "内网或本机目录须显式开启允许内网连接" }
        return trimmed
    }

    fun validateResolvedDownloadUrl(catalogUrl: String, resolvedUrl: String, lanAllowed: Boolean): String {
        val safeUrl = PluginMetadataValidator.requireOptionalHttpUrl(resolvedUrl, "downloadUrl")
            ?: throw IllegalArgumentException("Invalid plugin downloadUrl")
        val targetUri = URI(safeUrl)
        val targetScheme = targetUri.scheme.lowercase(Locale.ROOT)
        val targetHost = targetUri.host
        val catalogScheme = runCatching { URI(catalogUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (catalogScheme == "https" && targetScheme != "https") {
            require(lanAllowed && isLanHost(targetHost)) { "HTTPS 目录不得降级到非 HTTPS 下载地址" }
        }
        if (targetScheme == "http") {
            require(lanAllowed && isLanHost(targetHost)) { "公网插件包下载必须使用 HTTPS" }
        }
        return safeUrl
    }

    fun normalizeFingerprint(raw: String): String {
        val compact = raw.replace(":", "").replace(" ", "").trim().uppercase(Locale.ROOT)
        require(hex64Pattern.matches(compact)) { "证书指纹须为 64 位 SHA-256 十六进制字符串" }
        return compact.chunked(2).joinToString(":")
    }

    fun parseFingerprints(raw: String): Set<String> = raw
        .split(',', ';', '\n', '\r', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::normalizeFingerprint)
        .toSet()

    fun validateCustomSource(source: CustomPluginSource): CustomPluginSource {
        val id = validateSourceId(source.id)
        val name = PluginMetadataValidator.sanitizeOptionalText(source.name, maxLength = 80)
            ?: throw IllegalArgumentException("来源名称不能为空")
        val url = validateCatalogUrl(source.catalogUrl, source.lanAllowed)
        val fingerprints = source.trustedFingerprints.map(::normalizeFingerprint).toSet()
        return source.copy(
            id = id,
            name = name,
            catalogUrl = url,
            trustedFingerprints = fingerprints,
        )
    }

    /** Custom sources never overwrite or weaken built-in official/third-party trust anchors. */
    fun mergeTrustAnchors(
        builtIn: Map<String, Set<String>>,
        customSources: List<CustomPluginSource>,
    ): Map<String, Set<String>> {
        val customAnchors = customSources
            .filter { !isReservedSourceId(it.id) && it.id !in builtIn.keys && it.trustedFingerprints.isNotEmpty() }
            .associate { it.id to it.trustedFingerprints }
        return customAnchors + builtIn
    }

    fun canDownloadFromSource(sourceType: PluginSourceType, safeMode: Boolean): Boolean =
        !(safeMode && sourceType != PluginSourceType.OFFICIAL)

    /** Never resolve arbitrary hostnames during validation; inspect literal/local host syntax only. */
    fun isLanHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || (!host.contains('.') && !host.contains(':'))) {
            return true
        }
        if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            return runCatching { isLanAddress(InetAddress.getByName(host)) }.getOrDefault(false)
        }
        return false
    }

    private fun isLanAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
}

class PluginSourceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun list(): List<CustomPluginSource> {
        val raw = preferences.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CustomPluginSource>>(raw) }
            .getOrDefault(emptyList())
            .filter { !PluginSourcePolicy.isReservedSourceId(it.id) }
    }

    @Synchronized
    fun find(id: String): CustomPluginSource? = list().firstOrNull { it.id == id }

    @Synchronized
    fun add(source: CustomPluginSource): List<CustomPluginSource> {
        val validated = PluginSourcePolicy.validateCustomSource(source)
        val current = list()
        require(current.none { it.id.equals(validated.id, ignoreCase = true) }) { "来源 ID 已存在：${validated.id}" }
        require(current.none { it.catalogUrl.equals(validated.catalogUrl, ignoreCase = true) }) { "该目录地址已存在" }
        val updated = current + validated
        saveAll(updated)
        return updated
    }

    @Synchronized
    fun remove(id: String): List<CustomPluginSource> {
        val updated = list().filterNot { it.id == id }
        saveAll(updated)
        return updated
    }

    private fun saveAll(sources: List<CustomPluginSource>) {
        preferences.edit().putString(KEY_SOURCES, json.encodeToString(sources)).apply()
    }

    companion object {
        private const val PREFERENCES = "planlock_plugin_sources"
        private const val KEY_SOURCES = "custom_sources_v1"
    }
}
