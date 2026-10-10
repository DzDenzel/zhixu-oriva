package com.planlock.app.security

import android.content.Context
import com.planlock.plugin.PluginCapability
import com.planlock.plugin.PluginManifest
import com.planlock.plugin.PluginContribution

/** Neutral system lookup. Runtime actions are never treated as Android permission grants. */
fun PluginCapability.permissionKind(): PermissionKind? = when (this) {
    PluginCapability.USAGE_STATS -> PermissionKind.USAGE_STATS
    PluginCapability.ACCESSIBILITY -> PermissionKind.ACCESSIBILITY
    PluginCapability.DND -> PermissionKind.DND
    PluginCapability.NOTIFICATIONS -> PermissionKind.NOTIFICATIONS
    PluginCapability.LOCATION -> PermissionKind.LOCATION
    else -> null
}

fun grantedPluginPermissions(context: Context): Set<PluginCapability> {
    val states = PermissionChecker.check(context)
    return PluginCapability.entries.filterTo(mutableSetOf()) { states[it.permissionKind()] == true }
}

/** Re-read declarations at click time: a stale UI row may not request a different capability. */
fun canRequestPluginPermission(manifest: PluginManifest, request: PluginContribution.CapabilityRequest): Boolean =
    request in manifest.contributions && request.capabilityId in manifest.requiredCapabilities && request.capabilityId.permissionKind() != null

fun requestPluginPermission(context: Context, manifest: PluginManifest, request: PluginContribution.CapabilityRequest): Boolean {
    if (!canRequestPluginPermission(manifest, request)) return false
    PermissionIntent.open(context, requireNotNull(request.capabilityId.permissionKind()))
    return true
}
