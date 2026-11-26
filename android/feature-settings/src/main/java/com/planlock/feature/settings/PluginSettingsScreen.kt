package com.planlock.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.planlock.plugin.CustomPluginSource
import com.planlock.plugin.InstalledPluginRecord
import com.planlock.plugin.PluginCatalogClient
import com.planlock.plugin.PluginCatalogDocument
import com.planlock.plugin.PluginCatalogItem
import com.planlock.plugin.PluginInstallResult
import com.planlock.plugin.PluginPlatform
import com.planlock.plugin.PluginPlatformSnapshot
import com.planlock.plugin.PluginSourcePolicy
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
    val customSources by platform.customSources.collectAsState()
    var confirmDisableSafeMode by remember { mutableStateOf(false) }
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var removeSourceTarget by remember { mutableStateOf<CustomPluginSource?>(null) }
    var uninstallTarget by remember { mutableStateOf<InstalledPluginRecord?>(null) }
    val connection by com.planlock.app.api.ConnectionStore.state.collectAsState()
    val connectionConfig = connection.config ?: com.planlock.app.api.ConnectionConfig.default
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
    removeSourceTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { removeSourceTarget = null },
            title = { Text("移除自定义来源 ${source.name}？") },
            text = { Text("移除后将不再从该地址获取目录，其配置的信任锚也会一并撤销。") },
            confirmButton = {
                DestructiveButton("移除", onClick = {
                    platform.removeCustomSource(source.id)
                    removeSourceTarget = null
                    feedback("已移除自定义来源")
                })
            },
            dismissButton = { TextButton(onClick = { removeSourceTarget = null }) { Text("取消") } },
        )
    }
    if (showAddSourceDialog) {
        AddCustomPluginSourceDialog(
            existingIds = customSources.mapTo(mutableSetOf()) { it.id },
            connectionConfig = connectionConfig,
            onDismiss = { showAddSourceDialog = false },
            onAdd = { source ->
                platform.addCustomSource(source)
                showAddSourceDialog = false
                feedback("已添加自定义来源：${source.name}")
            },
        )
    }
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
                RepositoryRow("官方插件仓库", "仅接受官方信任锚签名的插件") {
                    platform.selectThirdPartySource(null)
                    onOpenRepository(PluginSourceType.OFFICIAL)
                }
            }
            if (!snapshot.safeMode) {
                item {
                    RepositoryRow("第三方插件仓库", "第三方来源不受项目审核或保证") {
                        platform.selectThirdPartySource(null)
                        onOpenRepository(PluginSourceType.THIRD_PARTY)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("自定义第三方来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = com.planlock.design.InkTone.text)
                    OutlinedButton(onClick = { showAddSourceDialog = true }) { Text("添加来源") }
                }
            }
            item {
                Text(
                    "自定义来源独立于内置官方/第三方信任锚；独立第三方源须使用 HTTPS（私有 HTTP 仅限与当前后端插件目录同源且开启内网时可用）；未配置信任锚的源禁止安装未验签包；安全模式下禁止访问与下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (customSources.isEmpty()) {
                item { Text("暂无自定义第三方来源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(customSources, key = { it.id }) { source ->
                    CustomSourceCard(
                        source = source,
                        safeMode = snapshot.safeMode,
                        onBrowse = {
                            platform.selectThirdPartySource(source.id)
                            onOpenRepository(PluginSourceType.THIRD_PARTY)
                        },
                        onRemove = { removeSourceTarget = source },
                    )
                }
            }
            item {
                Text("已安装插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = com.planlock.design.InkTone.text)
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
                Text(record.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
    LaunchedEffect(platform) { platform.snapshots.collect { platformSnapshot = it } }
    val customSources by platform.customSources.collectAsState()
    val selectedCustomId by platform.selectedThirdPartySourceId.collectAsState()
    val activeCustomSource = if (sourceType == PluginSourceType.THIRD_PARTY) {
        customSources.firstOrNull { it.id == selectedCustomId }
    } else null
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val sourcePath = if (sourceType == PluginSourceType.OFFICIAL) "official" else "third-party"
    val connection by com.planlock.app.api.ConnectionStore.state.collectAsState()
    val connectionConfig = connection.config ?: com.planlock.app.api.ConnectionConfig.default
    val catalogUrl = activeCustomSource?.catalogUrl ?: connectionConfig.catalogUrl(sourcePath)
    val lanAllowed = activeCustomSource?.lanAllowed ?: connectionConfig.lanAllowed
    val blockedBySafeMode = sourceType == PluginSourceType.THIRD_PARTY && platformSnapshot.safeMode

    suspend fun load() {
        if (blockedBySafeMode) {
            catalog = null
            loadError = null
            loading = false
            return
        }
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                val doc = client.fetch(catalogUrl, lanAllowed = lanAllowed)
                if (activeCustomSource != null) {
                    PluginSourcePolicy.validateCatalogForCustomSource(activeCustomSource, doc)
                }
                Result.success(doc)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
        catalog = result.getOrNull()?.takeIf { it.sourceType == sourceType }
        loadError = if (catalog == null) "仓库读取失败：${result.exceptionOrNull()?.message ?: "来源不匹配"}" else null
        loading = false
    }
    LaunchedEffect(sourceType, catalogUrl, blockedBySafeMode) { load() }
    val visible = catalog?.plugins.orEmpty().filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }

    val title = when {
        sourceType == PluginSourceType.OFFICIAL -> "官方插件仓库"
        activeCustomSource != null -> activeCustomSource.name
        else -> "第三方插件仓库"
    }
    PluginScaffold(title = title, onBack = onBack, snackbar = snackbar) { padding ->
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
                if (customSources.isNotEmpty() && !blockedBySafeMode) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = activeCustomSource == null,
                                onClick = { platform.selectThirdPartySource(null) },
                                label = { Text("内置第三方仓库") },
                            )
                            customSources.forEach { custom ->
                                FilterChip(
                                    selected = activeCustomSource?.id == custom.id,
                                    onClick = { platform.selectThirdPartySource(custom.id) },
                                    label = { Text(custom.name) },
                                )
                            }
                        }
                    }
                }
                if (activeCustomSource != null && activeCustomSource.trustedFingerprints.isEmpty()) {
                    item {
                        PersistentStatus(
                            "未配置信任锚 · 仅目录发现",
                            "当前自定义来源未登记受信任签名证书指纹，系统不会跳过签名校验；安装任何未信任签名包都会被安全拒绝。",
                        )
                    }
                }
            }
            if (blockedBySafeMode) {
                item {
                    UIStatePanel(UiState.EMPTY, "安全模式已开启，已禁止访问和下载任意第三方插件源", emptySubject = "插件")
                }
            } else {
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
                                val doc = catalog!!
                                if (activeCustomSource != null) {
                                    PluginSourcePolicy.validateCatalogForCustomSource(activeCustomSource, doc)
                                }
                                val result = withContext(Dispatchers.IO) {
                                    platform.install(item.toEntry(doc), activeCustomSource)
                                }
                                platformSnapshot = platform.snapshot()
                                when (result) {
                                    is PluginInstallResult.Installed -> "${result.manifest.name} 已安装，默认保持停用"
                                    is PluginInstallResult.Failed -> "安装失败：${formatPluginInstallError(result.message)}"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: IllegalArgumentException) { "安装失败：${failure.message ?: "来源校验未通过"}" }
                            catch (_: Exception) { "安装失败，请重试" }
                            finally { installingId = null }
                            snackbar.showSnackbar(message)
                        }
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
    val context = LocalContext.current
    val metadata = formatPluginMetadata(item.author, item.license, item.homepage, item.repository)
    PluginCard {
        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(item.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(pluginSourceLabel(sourceType), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(presentation.statusLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (item.description.isNotBlank()) Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        metadata.author?.let { Text("作者：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        metadata.license?.let { Text("许可证：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        metadata.homepageUrl?.let { url ->
            Text(
                "主页：$url",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { safeOpenExternalUrl(context, url) },
            )
        }
        metadata.repositoryUrl?.let { url ->
            Text(
                "源码仓库：$url",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { safeOpenExternalUrl(context, url) },
            )
        }
        Text("申请能力：${item.requiredCapabilities.joinToString("、") { pluginCapabilityLabel(it) }.ifBlank { "无" }}", style = MaterialTheme.typography.bodyMedium)
        PersistentStatus("技术标识 · 详情", "插件：${item.id}\n协议：v${item.contractVersion}\n来源类型：${sourceType.name}\n能力：${item.requiredCapabilities.joinToString { it.name }}")
        Button(onClick = onInstall, enabled = presentation.installAvailable && actionsEnabled) { Text(if (installing) "正在下载并校验…" else presentation.actionLabel) }
    }
}

@Composable
private fun CustomSourceCard(
    source: CustomPluginSource,
    safeMode: Boolean,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
) {
    PluginCard {
        Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(source.catalogUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("来源 ID：${source.id} · ${if (source.lanAllowed) "允许指定内网" else "仅限 HTTPS"}", style = MaterialTheme.typography.bodySmall)
        Text(
            customSourceTrustLabel(source),
            style = MaterialTheme.typography.bodySmall,
            color = if (source.trustedFingerprints.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBrowse, enabled = !safeMode) {
                Text(if (safeMode) "安全模式已禁用" else "浏览目录")
            }
            TextButton(onClick = onRemove) { Text("删除") }
        }
    }
}

@Composable
private fun AddCustomPluginSourceDialog(
    existingIds: Set<String>,
    connectionConfig: com.planlock.app.api.ConnectionConfig,
    onDismiss: () -> Unit,
    onAdd: (CustomPluginSource) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var catalogUrl by remember { mutableStateOf("") }
    var sourceIdInput by remember { mutableStateOf("") }
    var fingerprintsInput by remember { mutableStateOf("") }
    var lanAllowed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义第三方来源") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("来源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it; errorMessage = null },
                    label = { Text("目录 JSON 地址") },
                    placeholder = { Text("https://example.org/catalog.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sourceIdInput,
                    onValueChange = { sourceIdInput = it; errorMessage = null },
                    label = { Text("来源 ID（配置信任锚时必填）") },
                    supportingText = { Text("未配指纹留空则自动生成（仅目录发现）；配置指纹时须与目录及签名包 sourceId 一致") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fingerprintsInput,
                    onValueChange = { fingerprintsInput = it; errorMessage = null },
                    label = { Text("信任锚指纹（可选）") },
                    supportingText = { Text("SHA-256 十六进制指纹；留空则禁止安装未验签包") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                PluginSwitchRow(
                    "允许同源内网地址",
                    "独立来源须用 HTTPS；私有 HTTP 仅限与当前连接配置的插件目录同源且开启内网时可用",
                    lanAllowed,
                ) {
                    lanAllowed = it
                    errorMessage = null
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val trimmedUrl = catalogUrl.trim()
                    val fingerprints = PluginSourcePolicy.parseFingerprints(fingerprintsInput)
                    if (fingerprints.isNotEmpty() && sourceIdInput.isBlank()) {
                        throw IllegalArgumentException("配置信任锚时请填写来源 ID（须与目录及签名包中的 sourceId 一致）")
                    }
                    PluginSourcePolicy.validateCatalogUrl(
                        raw = trimmedUrl,
                        lanAllowed = lanAllowed && connectionConfig.lanAllowed,
                        approvedLanOrigin = connectionConfig.pluginsCatalogUrl,
                    )
                    val resolvedId = if (sourceIdInput.isBlank()) {
                        PluginSourcePolicy.deriveSourceId(trimmedUrl, existingIds)
                    } else {
                        PluginSourcePolicy.validateSourceId(sourceIdInput)
                    }
                    onAdd(
                        CustomPluginSource(
                            id = resolvedId,
                            name = name,
                            catalogUrl = trimmedUrl,
                            lanAllowed = lanAllowed,
                            trustedFingerprints = fingerprints,
                        )
                    )
                } catch (error: IllegalArgumentException) {
                    errorMessage = error.message ?: "输入无效，请检查后重试"
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
