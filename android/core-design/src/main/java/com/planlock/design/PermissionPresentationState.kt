package com.planlock.design

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.security.AccessibilityHealth
import com.planlock.app.security.ObservationAvailability
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionKind
import com.planlock.app.security.observationAvailability
import com.planlock.app.security.permissionKind
import com.planlock.plugin.PluginCapability
import kotlinx.coroutines.delay

data class PermissionPresentation(val granted: Set<PluginCapability>, val observation: ObservationAvailability)

/** A resumed page rechecks grants and heartbeat freshness; no background polling or UI cache. */
@Composable
fun rememberPermissionPresentation(): PermissionPresentation {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun read(): PermissionPresentation {
        val states = PermissionChecker.check(context)
        val granted = PluginCapability.entries.filterTo(mutableSetOf()) { states[it.permissionKind()] == true }
        val confirmed = if (states[PermissionKind.ACCESSIBILITY] == true) AccessibilityHealth.HEALTHY else AccessibilityHealth.NOT_CONFIGURED
        return PermissionPresentation(granted, observationAvailability(confirmed, states[PermissionKind.USAGE_STATS] == true))
    }
    var presentation by remember(context) { mutableStateOf(read()) }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner, resumed) {
        if (resumed) while (true) {
            presentation = read()
            delay(1_000L)
        }
    }
    return presentation
}
