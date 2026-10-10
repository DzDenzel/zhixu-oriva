package com.planlock.app.engine

import com.planlock.plugin.PluginDecision

/** Reuses the existing host decision; response timers never classify or record applications. */
internal fun recheckPluginResponse(expected: PluginDecision, foregroundPackage: String?, interactive: Boolean = true, keyguardLocked: Boolean = false): PluginDecision? {
    if (!interactive || keyguardLocked) return null
    if (foregroundPackage != expected.packageName) return null
    val current = ExecutionEngine.inspect(foregroundPackage) ?: return null
    return expected.copy(shouldTrack = current.shouldTrack).takeIf { current.planId == expected.planId && current.shouldTrack }
}
