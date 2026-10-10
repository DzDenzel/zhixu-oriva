package com.planlock.design

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.planlock.app.security.canRequestPluginPermission
import com.planlock.app.security.grantedPluginPermissions
import com.planlock.app.security.permissionKind
import com.planlock.app.security.requestPluginPermission
import com.planlock.plugin.PluginContribution
import com.planlock.plugin.PluginManifest
import com.planlock.plugin.PluginCapability

// Process lifetime matches the sync coordinator's attempt identifiers. No persisted stale IDs.
internal val feedbackEvents = FeedbackEventLedger()

/** A continuing fact is always discoverable; opening its explanation never starts an action. */
@Composable
fun PersistentStatus(label: String, detail: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    if (compact) {
        Surface(onClick = { expanded = true },
            modifier = modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label },
            shape = RoundedCornerShape(V2Tokens.shapePill),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label.removeSuffix(" · 详情"), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        TextButton(onClick = { expanded = true }, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (expanded) StatusDetailsDialog(label, detail) { expanded = false }
}

class PluginPermissionPrompt(private val readGranted: () -> Set<PluginCapability>) {
    internal var pending by mutableStateOf<Pair<PluginManifest, PluginContribution.CapabilityRequest>?>(null)
    fun request(manifest: PluginManifest, request: PluginContribution.CapabilityRequest) {
        if (request.capabilityId !in readGranted() && canRequestPluginPermission(manifest, request)) pending = manifest to request
    }
    fun dismiss() { pending = null }
    fun confirm(onRequest: (PluginManifest, PluginContribution.CapabilityRequest) -> Unit) {
        val (manifest, request) = pending ?: return
        pending = null
        if (request.capabilityId !in readGranted() && canRequestPluginPermission(manifest, request)) {
            onRequest(manifest, request)
        }
    }
}

/** A user may cancel before special-access settings; grants are checked again at confirmation. */
@Composable
fun rememberPluginPermissionPrompt(): PluginPermissionPrompt {
    val context = LocalContext.current
    val prompt = remember(context) { PluginPermissionPrompt { grantedPluginPermissions(context) } }
    val granted = rememberPermissionPresentation().granted
    prompt.pending?.let { (manifest, request) ->
        if (request.capabilityId !in granted) AlertDialog(
            onDismissRequest = prompt::dismiss,
            title = { Text(request.capabilityId.permissionKind()?.title ?: "授权说明") },
            text = { Text("${request.reason}\n\n将打开系统设置。可以取消或返回；未授权时计划仍可继续执行。") },
            confirmButton = { TextButton(onClick = {
                prompt.confirm { currentManifest, currentRequest -> requestPluginPermission(context, currentManifest, currentRequest) }
            }) { Text("前往系统设置") } },
            dismissButton = { TextButton(onClick = prompt::dismiss) { Text("取消") } },
        )
        else LaunchedEffect(request) { prompt.dismiss() }
    }
    return prompt
}

