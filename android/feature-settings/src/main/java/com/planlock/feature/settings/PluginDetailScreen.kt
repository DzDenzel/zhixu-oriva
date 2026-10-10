package com.planlock.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.security.grantedPluginPermissions
import com.planlock.app.security.permissionKind
import com.planlock.app.security.requestPluginPermission
import com.planlock.plugin.PluginCapabilityCatalog
import com.planlock.plugin.PluginContribution
import com.planlock.plugin.PluginPlatform
import com.planlock.plugin.PluginRuntimeRegistry
import com.planlock.design.rememberPermissionPresentation
import com.planlock.design.rememberPluginPermissionPrompt
import com.planlock.design.DestructiveButton
import com.planlock.design.PersistentStatus
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState

@Composable
fun PluginDetailScreen(pluginId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val platform = remember { PluginPlatform.get(context) }
    val owner = LocalLifecycleOwner.current
    val loaded by PluginRuntimeRegistry.plugins.collectAsState()
    var snapshot by remember { mutableStateOf(platform.snapshot()) }
    LaunchedEffect(platform) { platform.snapshots.collect { snapshot = it } }
    val granted = rememberPermissionPresentation().granted
    val permissionPrompt = rememberPluginPermissionPrompt()
    var confirmUninstall by remember { mutableStateOf(false) }
    DisposableEffect(owner, platform) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            snapshot = platform.snapshot()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val record = snapshot.installed.firstOrNull { it.manifest.id == pluginId }
    if (confirmUninstall && record != null) AlertDialog(
        onDismissRequest = { confirmUninstall = false }, title = { Text("卸载 ${record.manifest.name}？") },
        text = { Text("此插件的界面贡献与响应将立即停止。") },
        confirmButton = { DestructiveButton("卸载", onClick = { platform.uninstall(pluginId); confirmUninstall = false; onBack() }) },
        dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } },
    )
    PluginScaffold("插件详情", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (record == null) item { UIStatePanel(UiState.EMPTY, "此插件已卸载或不存在", onBack, emptySubject = "插件") }
            else {
                val manifest = record.manifest
                item { PluginCard {
                    Text(manifest.name, style = MaterialTheme.typography.titleLarge)
                    Text("${manifest.version} · ${pluginSourceLabel(manifest.sourceType)}")
                    Text(manifest.description.ifBlank { "未提供整体说明" })
                    Text(when {
                        loaded.any { it.manifest.id == pluginId } -> "状态：已启用"
                        record.lastError != null -> "状态：加载失败"
                        !snapshot.globalEnabled -> "状态：插件总开关已关闭"
                        snapshot.safeMode && manifest.sourceType == com.planlock.plugin.PluginSourceType.THIRD_PARTY -> "状态：安全模式已暂停"
                        else -> "状态：未启用"
                    })
                    record.lastError?.let { PersistentStatus("加载失败 · 详情", it) }
                } }
                item { PluginCard {
                    Text("已声明的界面贡献", style = MaterialTheme.typography.titleMedium)
                    if (manifest.contributions.isEmpty()) Text("此版本未声明界面贡献")
                    manifest.contributions.forEach { contribution ->
                        Text(pluginSlotLabel(contribution.slot), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (contribution) {
                            is PluginContribution.FormSection -> {
                                Text(contribution.title)
                                contribution.fields.forEach { field -> Text(field.label, style = MaterialTheme.typography.bodyMedium) }
                            }
                            is PluginContribution.StatusLine -> Text(if (contribution.requiresCapability == null || contribution.requiresCapability in granted)
                                contribution.text else contribution.unavailableText.orEmpty())
                            is PluginContribution.ActionRow -> Text(contribution.label)
                            is PluginContribution.CapabilityRequest -> {
                                Text(contribution.reason)
                                val available = contribution.capabilityId in granted
                                Text("${pluginCapabilityLabel(contribution.capabilityId)}：${if (available) "已就绪" else "未开启或未运行"}")
                                if (!available) Button(onClick = {
                                    val current = platform.snapshot().installed.firstOrNull { it.manifest.id == pluginId }
                                    current?.let { permissionPrompt.request(it.manifest, contribution) }
                                }) { Text("要求授权") }
                            }
                        }
                    }
                } }
                item { PluginCard {
                    Text("能力声明", style = MaterialTheme.typography.titleMedium)
                    if (manifest.requiredCapabilities.isEmpty()) Text("无")
                    manifest.requiredCapabilities.forEach { capability ->
                        val kind = capability.permissionKind()
                        Text("${pluginCapabilityLabel(capability)} · ${when {
                            kind != null -> if (capability in granted) "已就绪" else "未开启或未运行"
                            capability in PluginCapabilityCatalog.registered -> "已注册，执行时检查可用性"
                            else -> "平台未支持"
                        }}")
                    }
                    PersistentStatus("技术标识 · 详情", "插件：${manifest.id}\n来源：${manifest.sourceId}\n来源类型：${manifest.sourceType.name}\n协议：v${manifest.contractVersion}\n类型：${manifest.type}\n能力：${manifest.requiredCapabilities.joinToString { it.name }}\n界面贡献：${manifest.contributions.joinToString { "${it.id} · ${it.slot}" }}")
                    OutlinedButton(onClick = { confirmUninstall = true }) { Text("卸载") }
                } }
            }
        }
    }
}
