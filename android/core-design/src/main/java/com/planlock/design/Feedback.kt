package com.planlock.design

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
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
        // 同步异常：右上角一枚琥珀色小圆钮（云 + 斜线），点开看详情；不占标题位置
        val warn = InkHue.amber.color()
        Box(
            modifier.size(36.dp).clip(CircleShape).background(warn.inkTint())
                .clickable { expanded = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp), tint = warn)
        }
    } else {
        TextButton(onClick = { expanded = true }, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (expanded) StatusDetailsDialog(label, detail) { expanded = false }
}

/**
 * 同步失败的就地提示（v4.0.2）：不再从页面中部浮出提示条，而是让页头右上角的琥珀小圆钮
 * 原地展开成一枚胶囊「同步未完成 · 重试」，停留 5 秒后收回成圆钮。
 * 点胶囊正文看详情，点「重试」立即重新同步；位置与平时的同步状态是同一处，用户不用找。
 */
@Composable
fun SyncFailureStatus(label: String, detail: String, announce: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val warn = InkHue.amber.color()
    Row(
        modifier.height(36.dp).clip(CircleShape)
            .background(warn.inkTint())
            .border(1.dp, warn.copy(alpha = if (announce) .35f else 0f), CircleShape)
            .animateContentSize(androidx.compose.animation.core.tween(280, easing = Ink.Easing)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { expanded = true }.semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(18.dp), tint = warn)
        }
        if (announce) {
            Text("同步未完成", style = MaterialTheme.typography.labelMedium, color = InkTone.text, maxLines = 1,
                modifier = Modifier.clickable { expanded = true })
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(14.dp).background(warn.copy(alpha = .4f)))
            Text("重试", style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = warn, maxLines = 1,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onRetry).padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .semantics { contentDescription = "重试同步" })
        }
    }
    if (expanded) StatusDetailsDialog(label, detail) { expanded = false }
}

/**
 * 未登录胶囊（v4.1）：中性色，不是失败。平时是「未登录 · 登录」；
 * 一次同步结束后展开为「未登录，未同步 · 登录」几秒。点任意处去账户页登录。
 */
@Composable
fun SyncLoginStatus(announce: Boolean, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val ink = InkTone.soft
    Row(
        modifier.height(36.dp).clip(CircleShape)
            .background(InkTone.raised.copy(alpha = .92f))
            .border(1.dp, if (announce) InkTone.soft.copy(alpha = .45f) else InkTone.hairline, CircleShape)
            .clickable(onClick = onLogin)
            .semantics(mergeDescendants = true) { contentDescription = if (announce) "未登录，本次没有同步，点按登录" else "未登录，点按登录" }
            .animateContentSize(androidx.compose.animation.core.tween(280, easing = Ink.Easing))
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PersonOff, null, Modifier.size(16.dp), tint = ink)
        Spacer(Modifier.width(6.dp))
        Text(if (announce) "未登录，未同步" else "未登录", style = MaterialTheme.typography.labelMedium,
            color = InkTone.text, maxLines = 1)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(14.dp).background(InkTone.hairline))
        Text("登录", style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = InkTone.accent, maxLines = 1)
    }
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

