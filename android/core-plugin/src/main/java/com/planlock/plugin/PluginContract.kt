package com.planlock.plugin

import android.content.Context
import android.view.View
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

const val CURRENT_PLUGIN_CONTRACT_VERSION = 2

@Serializable
enum class PluginType { @SerialName("dex") DEX, @SerialName("js") JS }

@Serializable
enum class PluginSourceType { OFFICIAL, THIRD_PARTY }

@Serializable
enum class PluginCapability { OVERLAY, HOME, HAPTIC, FEEDBACK, USAGE_STATS, ACCESSIBILITY, DND, NOTIFICATIONS, LOCATION, QUERY_TODAY, QUERY_PLAN, QUERY_REPORT }

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val contractVersion: Int,
    val entryPoint: String,
    val sourceId: String,
    val sourceType: PluginSourceType,
    val requiredCapabilities: Set<PluginCapability> = emptySet(),
    val description: String = "",
    val contributions: List<PluginContribution> = emptyList(),
    val type: PluginType = PluginType.DEX,
    val author: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
)

object PluginMetadataValidator {
    fun sanitizeOptionalText(raw: String?, maxLength: Int = 200): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(trimmed.length <= maxLength && trimmed.none { it.isISOControl() }) {
            "Invalid plugin metadata text"
        }
        return trimmed
    }

    fun safeHttpUrlOrNull(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > 2048 || trimmed.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return trimmed
    }

    fun requireOptionalHttpUrl(raw: String?, field: String): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return safeHttpUrlOrNull(trimmed) ?: throw IllegalArgumentException("Unsafe URL for $field")
    }
}

object PluginManifestCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): PluginManifest = json.decodeFromString<PluginManifest>(raw).let {
        require(it.id.isNotBlank() && it.entryPoint.isNotBlank() && it.sourceId.isNotBlank())
        require(it.contractVersion in 1..CURRENT_PLUGIN_CONTRACT_VERSION) {
            "Unsupported plugin contract ${it.contractVersion}"
        }
        require(it.type != PluginType.JS || it.contractVersion == 2) { "JS requires contract v2" }
        if (it.type == PluginType.JS) require(it.entryPoint.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Invalid JS entry function" }
        validateContributions(it)
        it.copy(
            author = PluginMetadataValidator.sanitizeOptionalText(it.author),
            homepage = PluginMetadataValidator.requireOptionalHttpUrl(it.homepage, "homepage"),
            repository = PluginMetadataValidator.requireOptionalHttpUrl(it.repository, "repository"),
            license = PluginMetadataValidator.sanitizeOptionalText(it.license),
        )
    }
}

data class PluginDecision(
    val planId: Long,
    val packageName: String,
    val appName: String?,
    val shouldTrack: Boolean,
)

interface PlanLockPlugin {
    fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities)
}

/** Host-owned restricted capabilities. No AccessibilityService instance crosses this boundary. */
interface PluginCapabilities {
    fun overlay(): OverlayCapability?
    fun home(): HomeCapability?
    fun haptic(): HapticCapability?
    fun feedback(): FeedbackCapability?
}

fun interface PluginViewFactory {
    fun create(context: Context): View
}

interface OverlayCapability {
    fun show(factory: PluginViewFactory, displayMillis: Long, onExpired: Runnable)
}

fun interface HomeCapability { fun requestHome() }
fun interface HapticCapability { fun perform() }
fun interface FeedbackCapability { fun emit(code: String) }

object EmptyPluginCapabilities : PluginCapabilities {
    override fun overlay(): OverlayCapability? = null
    override fun home(): HomeCapability? = null
    override fun haptic(): HapticCapability? = null
    override fun feedback(): FeedbackCapability? = null
}
