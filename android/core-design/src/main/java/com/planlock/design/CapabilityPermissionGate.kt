package com.planlock.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.planlock.app.security.Capability
import com.planlock.app.security.CapabilityAccess

/** Functional permission contract; layout can be replaced without changing grants or save semantics. */
class CapabilityPermissionGate internal constructor(val access: CapabilityAccess) {
    internal var pending by mutableStateOf<Capability?>(null)
    internal var waitingSettings: Capability? = null
    internal var waitingRuntime: Capability? = null
    internal var callback: (Boolean) -> Unit = {}
    internal var saveMissing by mutableStateOf<Set<Capability>>(emptySet())
    internal var saveAction: () -> Unit = {}
    fun request(capability: Capability, result: (Boolean) -> Unit = {}) {
        if (access.check(capability)) result(true) else { callback = result; pending = capability }
    }
    internal fun complete(capability: Capability) {
        val result = callback
        callback = {}; waitingSettings = null; waitingRuntime = null
        access.refresh(); result(access.check(capability))
    }
    fun beforeSave(required: Set<Capability>, save: () -> Unit) {
        access.refresh()
        val missing = required.filterTo(linkedSetOf()) { !access.check(it) }
        if (missing.isEmpty()) save() else { saveAction = save; saveMissing = missing }
    }
}

@Composable
fun rememberCapabilityPermissionGate(): CapabilityPermissionGate {
    val context = LocalContext.current
    val activity = remember(context) { context.permissionActivity() }
    val gate = remember(context.applicationContext) { CapabilityPermissionGate(CapabilityAccess(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { gate.waitingRuntime?.let(gate::complete) }
    DisposableEffect(gate) {
        val observation = gate.access.observe { gate.waitingSettings?.let(gate::complete) }
        onDispose { observation.close() }
    }
    gate.pending?.let { capability ->
        fun decline() { val result = gate.callback; gate.pending = null; gate.callback = {}; result(false) }
        val request = gate.access.request(capability)
        val missingRuntime = request.runtimePermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val preferences = context.getSharedPreferences("capability_requests", Context.MODE_PRIVATE)
        val asked = preferences.getBoolean(capability.name, false)
        val canAskAgain = missingRuntime.any { activity?.shouldShowRequestPermissionRationale(it) == true }
        if (capabilityRequestRoute(missingRuntime.isNotEmpty(), asked, canAskAgain) == CapabilityRequestRoute.SYSTEM_DIALOG) {
            // Trigger once after composition; no app panel precedes Android's runtime permission UI.
            LaunchedEffect(capability) {
                gate.pending = null
                preferences.edit().putBoolean(capability.name, true).apply()
                gate.waitingRuntime = capability
                runCatching { launcher.launch(missingRuntime.toTypedArray()) }.onFailure { gate.complete(capability) }
            }
        } else CapabilityPermissionSheet(capability, onOpen = {
            gate.pending = null
            gate.waitingSettings = capability
            runCatching { context.startActivity(gate.access.request(capability).settingsIntent) }.onFailure { gate.complete(capability) }
        }, onLater = ::decline)
    }
    if (gate.saveMissing.isNotEmpty()) CapabilitySaveCheckDialog(gate.saveMissing,
        onSaveAnyway = { val save = gate.saveAction; gate.saveMissing = emptySet(); gate.saveAction = {}; save() },
        onOpen = { capability -> gate.saveMissing = emptySet(); gate.saveAction = {}; gate.request(capability) },
        onDismiss = { gate.saveMissing = emptySet(); gate.saveAction = {} })
    return gate
}

private tailrec fun Context.permissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.permissionActivity()
    else -> null
}
