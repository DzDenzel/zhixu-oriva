package com.planlock.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.planlock.plugin.CustomPluginSource
import com.planlock.plugin.PluginCapability
import com.planlock.plugin.PluginMetadataValidator
import com.planlock.plugin.PluginSlots
import java.util.Locale

/** Human labels only; enum/IDs passed into the runtime stay untouched. */
internal fun pluginCapabilityLabel(capability: PluginCapability): String = when (capability) {
    PluginCapability.OVERLAY -> "覆盖层"
    PluginCapability.HOME -> "回桌面"
    PluginCapability.HAPTIC -> "震动"
    PluginCapability.FEEDBACK -> "反馈"
    PluginCapability.USAGE_STATS -> "使用情况访问"
    PluginCapability.ACCESSIBILITY -> "无障碍"
    PluginCapability.DND -> "勿扰"
    PluginCapability.NOTIFICATIONS -> "通知"
    PluginCapability.LOCATION -> "定位"
    PluginCapability.QUERY_TODAY -> "今日查询"
    PluginCapability.QUERY_PLAN -> "计划查询"
    PluginCapability.QUERY_REPORT -> "执行报告查询"
}

internal fun pluginSlotLabel(slot: String): String = when (slot) {
    PluginSlots.PLAN_DETAIL_PROTECTION -> "计划详情"
    PluginSlots.PLAN_EDIT_CONFIGURATION -> "计划编辑"
    else -> "其他界面位置"
}

internal fun devicePlatformLabel(platform: String?): String = when (platform?.lowercase(Locale.ROOT)) {
    "android" -> "Android"
    "windows" -> "Windows"
    "ios" -> "iOS"
    "macos", "mac" -> "macOS"
    "linux" -> "Linux"
    else -> platform?.takeIf { it.isNotBlank() } ?: "未知平台"
}

internal data class PluginMetadataPresentation(
    val author: String? = null,
    val license: String? = null,
    val homepageUrl: String? = null,
    val repositoryUrl: String? = null,
) {
    val hasAny: Boolean get() = author != null || license != null || homepageUrl != null || repositoryUrl != null
}

internal fun formatPluginMetadata(
    author: String?,
    license: String?,
    homepage: String?,
    repository: String?,
): PluginMetadataPresentation = PluginMetadataPresentation(
    author = PluginMetadataValidator.sanitizeOptionalText(author),
    license = PluginMetadataValidator.sanitizeOptionalText(license),
    homepageUrl = PluginMetadataValidator.safeHttpUrlOrNull(homepage),
    repositoryUrl = PluginMetadataValidator.safeHttpUrlOrNull(repository),
)

internal fun formatPluginInstallError(message: String): String = when {
    message.startsWith("No trust anchors registered for source ") ->
        "该第三方来源尚未配置信任锚，系统不会跳过签名校验，已拒绝安装。"
    message.startsWith("Plugin signature is not trusted") ->
        "插件签名未受该来源信任锚信任，已拒绝安装。"
    else -> message
}

internal fun customSourceTrustLabel(source: CustomPluginSource): String =
    if (source.trustedFingerprints.isEmpty()) {
        "信任锚：未配置（仅可浏览目录，安装将拒绝未验签包）"
    } else {
        "信任锚：已配置 ${source.trustedFingerprints.size} 个证书指纹"
    }

internal fun safeOpenExternalUrl(context: Context, url: String): Boolean {
    val safe = PluginMetadataValidator.safeHttpUrlOrNull(url) ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)))
        true
    }.getOrDefault(false)
}
