package com.planlock.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.security.grantedPluginPermissions
import com.planlock.app.security.requestPluginPermission
import com.planlock.design.DestructiveButton
import com.planlock.design.PluginContributionContent
import com.planlock.design.rememberPluginPermissionPrompt
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.planlock.plugin.PluginRuntimeRegistry
import com.planlock.plugin.PluginSlots
import com.planlock.plugin.PluginActionEvent
import com.planlock.plugin.PluginActionStatus
import com.planlock.plugin.PluginContribution
import com.planlock.plugin.BoundPluginContribution
import com.planlock.plugin.ContributionActionInput
import com.planlock.design.AppOption
import com.planlock.design.AppPickerSheet
import com.planlock.app.engine.AppPackageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.planlock.plugin.projectContributions
import com.planlock.plugin.visibleContributions
import com.planlock.design.rememberPermissionPresentation
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.ExecutionStatusCard
import com.planlock.design.PlanCard
import com.planlock.design.PlanLockColors
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import kotlinx.coroutines.launch
import java.util.Calendar
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(planId: Long, onEdit: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = com.planlock.app.sync.AppSync.get(context)) }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<PlanSnapshotEntity?>(null) }
    var strategy by remember { mutableStateOf<StrategySnapshotEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    val loadedPlugins by PluginRuntimeRegistry.plugins.collectAsState()
    val granted = rememberPermissionPresentation().granted
    val contributions = visibleContributions(projectContributions(loadedPlugins, PluginSlots.PLAN_DETAIL_PROTECTION), granted)
    var actionRunning by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var actionExplanation by remember { mutableStateOf<String?>(null) }
    actionExplanation?.let { text -> AlertDialog(onDismissRequest = { actionExplanation = null },
        title = { Text("插件说明") }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { actionExplanation = null }) { Text("关闭") } }) }
    val permissionPrompt = rememberPluginPermissionPrompt()
    var applicationAction by remember { mutableStateOf<Pair<BoundPluginContribution, PluginContribution.ActionRow>?>(null) }
    var actionApps by remember { mutableStateOf<List<AppOption>>(emptyList()) }


    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    suspend fun refresh() {
        plan = store.plan(planId)
        strategy = store.strategy(planId)
        loading = false
    }

    fun runContribution(bound: BoundPluginContribution, action: PluginContribution.ActionRow, app: AppOption? = null) {
        if (actionRunning) return
        actionRunning = true
        scope.launch {
            val message = try {
                val currentPlan = store.plan(planId)
                val until = currentPlan?.let { PlanTime.activeUntilMillis(it, Calendar.getInstance()) } ?: 0
                val result = PluginRuntimeRegistry.dispatchAction(bound.pluginId,
                    PluginActionEvent(planId, action.actionId, app?.id, app?.name, until))
                result.message ?: when (result.status) {
                    PluginActionStatus.EXECUTED -> "操作已完成"
                    PluginActionStatus.UNAVAILABLE -> "此动作当前不可用"
                    PluginActionStatus.FAILED_OPEN -> "此动作执行失败，计划继续执行"
                }
            } finally { actionRunning = false }
            if ('\n' in message || message.length > 120) actionExplanation = message
            else snackbar.showSnackbar(message)
        }
    }

    LaunchedEffect(applicationAction) {
        if (applicationAction != null) actionApps = withContext(Dispatchers.IO) {
            AppPackageResolver(context).installedApps().map { AppOption(it.packageName, it.name, it.category) }
        }
    }
    LaunchedEffect(loadedPlugins) {
        applicationAction?.let { pending ->
            if (loadedPlugins.none { it.manifest.id == pending.first.pluginId && pending.second in it.manifest.contributions })
                applicationAction = null
        }
    }
    applicationAction?.let { pending ->
        AppPickerSheet(title = pending.second.label, apps = actionApps, selectedIds = emptySet(),
            onToggle = { app -> applicationAction = null; runContribution(pending.first, pending.second, app) },
            onQuickAdd = {}, onDismiss = { applicationAction = null }, singleSelection = true)
    }

    fun requestAction(value: PlanSnapshotEntity, action: ProtectedPlanAction, after: () -> Unit = {}) {
        scope.launch {
            requestOrRunPlanAction(
                actionGate, value, action,
                onPrompt = { pendingAction = it },
                onCompleted = {
                    executionHaptic(context)
                    after()
                    scope.launch { refresh() }
                },
            )
        }
    }

    LaunchedEffect(planId) { refresh() }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("确认删除计划？") },
            text = { Text("删除后无法恢复，执行记录仍会保留。") },
            confirmButton = {
                DestructiveButton("删除", onClick = {
                    showDelete = false
                    plan?.let { requestAction(it, ProtectedPlanAction.DELETE, onBack) }
                })
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
        TopAppBar(
            title = { Text("计划详情") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "编辑") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { padding ->
        when {
            loading -> UIStatePanel(UiState.LOADING, modifier = Modifier.padding(padding))
            plan == null -> UIStatePanel(UiState.ERROR, "计划不存在或已删除", onBack, Modifier.padding(padding))
            else -> {
                val value = plan!!
                val clock = Calendar.getInstance()
                val now = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
                val active = PlanTime.isActive(value, now, OccurrenceDate.from(clock))
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        if (active) {
                            val remaining = PlanTime.remainingMinutes(value, now)
                            ExecutionStatusCard(
                                planName = value.name,
                                remainingText = "剩余 %02d:%02d".format(remaining / 60, remaining % 60),
                                endsAtText = "${PlanTime.format(value.endTime)} 结束 · 已完成 ${(PlanTime.progress(value, now) * 100).toInt()}%",
                                progress = PlanTime.progress(value, now),
                                paused = value.status != 1,
                                onTogglePause = { requestAction(value, ProtectedPlanAction.PAUSE) },
                                sharedKey = "plan-${value.id}"
                            )
                        } else {
                            PlanCard(value.toCardData(if (value.status == 1) PlanVisualState.UPCOMING else PlanVisualState.PAUSED), onClick = onEdit, sharedKey = "plan-${value.id}")
                        }
                    }
                    item { DetailSection("基本信息", listOf("类型" to value.type, "重复" to repeatLabel(value.repeatRule), "提醒" to (value.remindAdvanceMin?.let { "提前 $it 分钟" } ?: "未设置"), "跨天" to if ((PlanTime.minutesOfDay(value.endTime) ?: 0) <= (PlanTime.minutesOfDay(value.startTime) ?: 0)) "是" else "否")) }
                    item { StrategySection(strategy) }
                    if (contributions.isNotEmpty()) item {
                        PluginContributionContent(contributions, granted,
                            onAction = { bound, action ->
                                if (action.input == ContributionActionInput.APPLICATION_SINGLE) applicationAction = bound to action
                                else runContribution(bound, action)
                            },
                            onRequest = { bound, request ->
                                PluginRuntimeRegistry.plugins.value.firstOrNull { it.manifest.id == bound.pluginId }
                                    ?.let { permissionPrompt.request(it.manifest, request) }
                            },
                            actionsEnabled = !actionRunning,
                        )
                        if (actionRunning) Text("正在执行操作…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = { requestAction(value, ProtectedPlanAction.PAUSE) }, modifier = Modifier.weight(1f)) { Text(if (value.status == 1) "提前结束" else "恢复") }
                            FilledTonalButton(onClick = { showDelete = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Delete, null); Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategySection(strategy: StrategySnapshotEntity?) {
    val whitelist = parseAppReferences(strategy?.whitelistApps)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("执行范围", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Text("免打扰", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strategyStateText(strategy?.autoDnd))
            }
            Row(Modifier.fillMaxWidth()) {
                Text("通知静默", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strategyStateText(strategy?.notifySilence))
            }
            Text(if (whitelist.isEmpty()) "全部应用" else "不计入分心：${whitelist.joinToString("、") { it.name }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailSection(title: String, rows: List<Pair<String, String>>) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, Modifier.weight(2f))
                }
            }
        }
    }
}

internal data class AppReference(val id: String, val name: String)

internal fun parseAppReferences(raw: String?): List<AppReference> {
    if (raw.isNullOrBlank()) return emptyList()
    // Android ICU requires the closing brace to be escaped as well.
    val jsonEntries = Regex("\\{[^}]*\\}").findAll(raw).mapNotNull { entry ->
        val name = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(entry.value)?.groupValues?.get(1)
        val packageName = Regex("\\\"packageName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(entry.value)?.groupValues?.get(1)
        name?.let { AppReference(packageName ?: it, it) }
    }.toList()
    if (jsonEntries.isNotEmpty()) return jsonEntries
    return raw.trim('[', ']').split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.map { AppReference(it, it) }
}

internal fun parseAppNames(raw: String?): List<String> = parseAppReferences(raw).map { it.name }

