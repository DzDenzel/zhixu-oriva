package com.planlock.feature.settings

import com.planlock.plugin.PluginCapability
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
