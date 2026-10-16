package com.planlock.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.design.PresentationCard
import com.planlock.design.SettingSwitch
import com.planlock.design.DestructiveButton
import com.planlock.design.PersistentStatus
import com.planlock.design.UIStatePanel
import com.planlock.design.RepositoryFailurePanel
import com.planlock.design.UiState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CancellationException
import com.planlock.design.PageAtmosphere
import com.planlock.design.planLockAtmosphere
import com.planlock.plugin.InstalledPluginRecord
import com.planlock.plugin.PluginCatalogClient
import com.planlock.plugin.PluginCatalogDocument
import com.planlock.plugin.PluginCatalogItem
import com.planlock.plugin.PluginInstallResult
import com.planlock.plugin.PluginPlatform
import com.planlock.plugin.PluginPlatformSnapshot
import com.planlock.plugin.PluginSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsScreen(
    onBack: () -> Unit,
    onOpenRepository: (PluginSourceType) -> Unit,
    onOpenPlugin: (String) -> Unit,
) {
    val context = LocalContext.current
    val platform = remember { PluginPlatform.get(context) }
    var snapshot by remember { mutableStateOf(platform.snapshot()) }
    LaunchedEffect(platform) { platform.snapshots.collect { snapshot = it } }
    var confirmDisableSafeMode by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<InstalledPluginRecord?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun feedback(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    fun refresh() { snapshot = platform.snapshot() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, platform) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (confirmDisableSafeMode) {
        AlertDialog(
            onDismissRequest = { confirmDisableSafeMode = false },
            title = { Text("关闭安全模式？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("项目不审核、不背书，也不保证任何第三方插件源或其中的插件。")
                    Text("第三方插件与知序运行在同一应用进程，当前无法通过技术手段实现进程级隔离。")
                    Text("确认后即可访问、搜索和下载第三方插件，不会再次逐项弹窗确认。")
                }
            },
            confirmButton = {
                Button(onClick = {
                    platform.setSafeMode(false)
                    confirmDisableSafeMode = false
                    refresh()
                    feedback("安全模式已关闭")
                }) { Text("理解风险并关闭") }
            },
            dismissButton = { TextButton(onClick = { confirmDisableSafeMode = false }) { Text("保持开启") } },
        )
    }

    uninstallTarget?.let { record -> AlertDialog(
        onDismissRequest = { uninstallTarget = null },
        title = { Text("卸载 ${record.manifest.name}？") },
        text = { Text("此插件的界面贡献与响应将立即停止。") },
        confirmButton = { DestructiveButton("卸载", onClick = {
            platform.uninstall(record.manifest.id)
            uninstallTarget = null
            refresh()
            feedback("插件已卸载")
        }) },
        dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text("取消") } },
    ) }
    PluginScaffold(title = "插件", onBack = onBack, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PluginCard {
                    PluginSwitchRow("插件总开关", "关闭后所有插件停止接收判定结果", snapshot.globalEnabled) {
                        platform.setGlobalEnabled(it); refresh(); feedback(if (it) "插件已开启" else "插件已关闭")
                    }
                    HorizontalDivider()
                    PluginSwitchRow("安全模式", "开启时隐藏并停用所有第三方插件", snapshot.safeMode) { enabled ->
                        if (enabled) { platform.setSafeMode(true); refresh(); feedback("安全模式已开启") } else confirmDisableSafeMode = true
                    }
                }
            }
            item {
                RepositoryRow("官方插件仓库", "仅接受官方信任锚签名的插件") { onOpenRepository(PluginSourceType.OFFICIAL) }
            }
            if (!snapshot.safeMode) {
                item {
                    RepositoryRow("第三方插件仓库", "第三方来源不受项目审核或保证") { onOpenRepository(PluginSourceType.THIRD_PARTY) }
                }
            }
            item {
                Text("已安装插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = com.planlock.design.InkTone.text)
            }
            if (snapshot.installed.isEmpty()) {
                item { UIStatePanel(UiState.EMPTY, "暂无已安装插件", emptySubject = "插件") }
            } else {
                items(snapshot.installed, key = { it.manifest.id }) { record ->
                    InstalledPluginCard(record, snapshot, onOpen = { onOpenPlugin(record.manifest.id) }) { action ->
                        when (action) {
                            InstalledAction.TOGGLE -> {
                                platform.setPluginEnabled(record.manifest.id, !record.enabled)
                                feedback(if (record.enabled) "插件已停用" else "已请求启用插件")
                            }
                            InstalledAction.UNINSTALL -> uninstallTarget = record
                        }
                        refresh()
                    }
                }
            }
        }
    }
}

private enum class InstalledAction { TOGGLE, UNINSTALL }

@Composable
private fun InstalledPluginCard(
    record: InstalledPluginRecord,
    snapshot: PluginPlatformSnapshot,
    onOpen: () -> Unit,
    onAction: (InstalledAction) -> Unit,
) {
    val suspendedBySafeMode = snapshot.safeMode && record.manifest.sourceType == PluginSourceType.THIRD_PARTY
    PluginCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(record.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${record.manifest.version} · ${pluginSourceLabel(record.manifest.sourceType)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = record.enabled, enabled = !suspendedBySafeMode, onCheckedChange = { onAction(InstalledAction.TOGGLE) })
        }
        if (suspendedBySafeMode) Text("安全模式已暂停此插件", color = MaterialTheme.colorScheme.primary)
        record.lastError?.let { PersistentStatus("加载失败 · 详情", it) }
        Text("查看说明与能力", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { onAction(InstalledAction.UNINSTALL) }) { Text("卸载") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginRepositoryScreen(sourceType: PluginSourceType, onBack: () -> Unit) {
    val context = LocalContext.current
    val platform = remember { PluginPlatform.get(context) }
    val client = remember { PluginCatalogClient() }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<PluginCatalogDocument?>(null) }
    var platformSnapshot by remember { mutableStateOf(platform.snapshot()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val sourcePath = if (sourceType == PluginSourceType.OFFICIAL) "official" else "third-party"
    val connection by com.planlock.app.api.ConnectionStore.state.collectAsState()
    val catalogUrl = (connection.config ?: com.planlock.app.api.ConnectionConfig.default).catalogUrl(sourcePath)

    suspend fun load() {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try { Result.success(client.fetch(catalogUrl)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
        catalog = result.getOrNull()?.takeIf { it.sourceType == sourceType }
        loadError = if (catalog == null) "仓库读取失败：${result.exceptionOrNull()?.message ?: "来源不匹配"}" else null
        loading = false
    }
    LaunchedEffect(sourceType, catalogUrl) { load() }
    val visible = catalog?.plugins.orEmpty().filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }

    PluginScaffold(title = if (sourceType == PluginSourceType.OFFICIAL) "官方插件仓库" else "第三方插件仓库", onBack = onBack, snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sourceType == PluginSourceType.THIRD_PARTY) {
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            "第三方插件不由项目审核、背书或保证，并与知序运行在同一进程，无法实现技术隔离。请自行判断来源可信度。",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索插件") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            when {
                loading -> item { UIStatePanel(UiState.LOADING, "正在读取仓库") }
                loadError != null -> item { RepositoryFailurePanel(loadError!!) { scope.launch { load() } } }
                visible.isEmpty() -> item { UIStatePanel(UiState.EMPTY, if (query.isBlank()) "仓库暂无插件" else "未找到匹配插件", emptySubject = "插件") }
                else -> item { Text("${visible.size} 个插件", style = MaterialTheme.typography.labelMedium) }
            }
            items(visible, key = { it.id }) { item ->
                val installed = platformSnapshot.installed.firstOrNull { it.manifest.id == item.id }
                val presentation = catalogPluginPresentation(
                    isInstalled = installed != null,
                    isEnabled = installed?.enabled == true,
                )
                CatalogPluginCard(item, sourceType, presentation, installingId == item.id, installingId == null) {
                    if (installingId == null) scope.launch {
                        installingId = item.id
                        val message = try {
                            val result = withContext(Dispatchers.IO) { platform.install(item.toEntry(catalog!!)) }
                            platformSnapshot = platform.snapshot()
                            when (result) {
                                is PluginInstallResult.Installed -> "${result.manifest.name} 已安装，默认保持停用"
                                is PluginInstallResult.Failed -> "安装失败：${result.message}"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { "安装失败，请重试" }
                        finally { installingId = null }
                        snackbar.showSnackbar(message)
                    }
                }
            }
        }
    }
}

internal data class CatalogPluginPresentation(
    val statusLabel: String,
    val actionLabel: String,
    val installAvailable: Boolean,
)

internal fun catalogPluginPresentation(isInstalled: Boolean, isEnabled: Boolean): CatalogPluginPresentation = when {
    isEnabled -> CatalogPluginPresentation("状态：已启用", "已启用", false)
    isInstalled -> CatalogPluginPresentation("状态：已安装（未启用）", "已安装", false)
    else -> CatalogPluginPresentation("状态：未安装", "下载并安装", true)
}

@Composable
private fun CatalogPluginCard(
    item: PluginCatalogItem,
    sourceType: PluginSourceType,
    presentation: CatalogPluginPresentation,
    installing: Boolean,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
) {
    PluginCard {
        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(item.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(pluginSourceLabel(sourceType), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(presentation.statusLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (item.description.isNotBlank()) Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("申请能力：${item.requiredCapabilities.joinToString("、") { pluginCapabilityLabel(it) }.ifBlank { "无" }}", style = MaterialTheme.typography.bodyMedium)
        PersistentStatus("技术标识 · 详情", "插件：${item.id}\n协议：v${item.contractVersion}\n来源类型：${sourceType.name}\n能力：${item.requiredCapabilities.joinToString { it.name }}")
        Button(onClick = onInstall, enabled = presentation.installAvailable && actionsEnabled) { Text(if (installing) "正在下载并校验…" else presentation.actionLabel) }
    }
}

internal fun pluginSourceLabel(sourceType: PluginSourceType): String = when (sourceType) {
    PluginSourceType.OFFICIAL -> "来源：官方"
    PluginSourceType.THIRD_PARTY -> "来源：第三方"
}

@Composable
private fun RepositoryRow(title: String, summary: String, onClick: () -> Unit) {
    PluginCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "进入")
        }
    }
}

@Composable
private fun PluginSwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingSwitch(title, summary, checked, onCheckedChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginScaffold(title: String, onBack: () -> Unit, snackbar: SnackbarHostState? = null, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent), title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) },
        snackbarHost = { snackbar?.let { com.planlock.design.InkSnackbarHost(it) } },
        content = content,
    )
}

@Composable
internal fun PluginCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    PresentationCard(modifier, content)
}
