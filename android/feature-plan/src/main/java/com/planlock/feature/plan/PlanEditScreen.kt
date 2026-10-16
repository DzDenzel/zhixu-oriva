package com.planlock.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.planlock.design.planLockAtmosphere
import androidx.core.graphics.drawable.toBitmap
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.dto.AppItem
import com.planlock.app.api.dto.PlanUpsertRequest
import com.planlock.app.api.dto.RepeatRule
import com.planlock.app.api.dto.StrategyUpsertRequest
import com.planlock.app.engine.AppPackageResolver
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.StrategySnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionIntent
import com.planlock.app.security.PermissionKind
import com.planlock.design.PresentationCard
import com.planlock.design.PickerField
import com.planlock.design.AppOption
import com.planlock.design.AppPickerSheet
import com.planlock.design.PlanTimePicker
import com.planlock.design.PlanTypeSegmented
import com.planlock.design.ReminderStepper
import com.planlock.design.RepeatPreset
import com.planlock.design.RepeatRuleSelector
import com.planlock.design.SettingSwitch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.planlock.plugin.*
import com.planlock.design.PluginFormContent
import com.planlock.design.PluginContributionContent
import com.planlock.design.rememberPermissionPresentation
import com.planlock.design.rememberPluginPermissionPrompt
import com.planlock.app.api.TokenStore
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

private enum class AppListKind { WHITE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(planId: Long?, onDone: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val sync = remember { com.planlock.app.sync.AppSync.get(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = sync) }
    val resolver = remember { AppPackageResolver(context) }
    val scope = rememberCoroutineScope()
    val loadedPlugins by PluginRuntimeRegistry.plugins.collectAsState()
    val granted = rememberPermissionPresentation().granted
    val editorContributions = visibleContributions(projectContributions(loadedPlugins, PluginSlots.PLAN_EDIT_CONFIGURATION), granted)
    val draftPlanId = rememberSaveable(planId) { planId ?: -System.currentTimeMillis() }
    val configuration = remember { PluginConfigurationStore(context) }
    val snackbar = remember { SnackbarHostState() }
    var pluginBusy by remember { mutableStateOf(false) }
    var configRevision by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<AppOption>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    val permissionPrompt = rememberPluginPermissionPrompt()
    var applicationAction by remember { mutableStateOf<Pair<BoundPluginContribution, PluginContribution.ActionRow>?>(null) }
    var actionExplanation by remember { mutableStateOf<String?>(null) }

    suspend fun showPluginResult(message: String) {
        if ('\n' in message || message.length > 120) actionExplanation = message
        else snackbar.showSnackbar(message)
    }

    fun runContribution(bound: BoundPluginContribution, action: PluginContribution.ActionRow, app: AppOption? = null) {
        if (pluginBusy) return
        pluginBusy = true
        scope.launch {
            val message = try {
                // Use the saved occurrence, never the editor's uncommitted time fields.
                val saved = planId?.let { store.plan(it) }
                val until = saved?.let { PlanTime.activeUntilMillis(it, java.util.Calendar.getInstance()) } ?: 0L
                when {
                    action.input == ContributionActionInput.APPLICATION_SINGLE && saved == null -> "请先保存计划，再使用限时放松"
                    action.input == ContributionActionInput.APPLICATION_SINGLE && until == 0L -> "当前计划未在执行，限时放松仅在执行期间可用"
                    action.input == ContributionActionInput.APPLICATION_SINGLE && app == null -> {
                        if (PluginRuntimeRegistry.plugins.value.any { it.manifest.id == bound.pluginId && action in it.manifest.contributions }) {
                            applicationAction = bound to action
                            null
                        } else "此动作当前不可用"
                    }
                    else -> {
                        val result = PluginRuntimeRegistry.dispatchAction(bound.pluginId,
                            PluginActionEvent(saved?.id ?: configuration.canonicalPlanId(TokenStore.localDataUserId, draftPlanId), action.actionId, app?.id, app?.name, until))
                        result.message ?: when (result.status) {
                            PluginActionStatus.EXECUTED -> "操作已完成"
                            PluginActionStatus.UNAVAILABLE -> "此动作当前不可用"
                            PluginActionStatus.FAILED_OPEN -> "此动作执行失败，计划继续执行"
                        }
                    }
                }
            } finally { pluginBusy = false }
            message?.let { showPluginResult(it) }
        }
    }

    LaunchedEffect(loadedPlugins, applicationAction) {
        applicationAction?.let { pending ->
            if (loadedPlugins.none { it.manifest.id == pending.first.pluginId && pending.second in it.manifest.contributions })
                applicationAction = null
        }
    }
    applicationAction?.let { pending ->
        AppPickerSheet(title = pending.second.label, apps = installed, selectedIds = emptySet(),
            onToggle = { app -> applicationAction = null; runContribution(pending.first, pending.second, app) },
            onQuickAdd = {}, onDismiss = { applicationAction = null }, singleSelection = true)
    }
    actionExplanation?.let { message ->
        AlertDialog(onDismissRequest = { actionExplanation = null }, title = { Text("插件说明") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { actionExplanation = null }) { Text("关闭") } })
    }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("学习") }
    var startTime by remember { mutableStateOf("21:00") }
    var endTime by remember { mutableStateOf("23:00") }
    var reminder by remember { mutableStateOf<Int?>(15) }
    var preset by remember { mutableStateOf(RepeatPreset.WEEKDAY) }
    var weekdays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var strategyDraft by remember(planId) { mutableStateOf(PlanStrategyDraft.fromSnapshot(null, isNewPlan = planId == null)) }
    var strategyLoaded by remember(planId) { mutableStateOf(planId == null) }
    var originalWhitelist by remember(planId) { mutableStateOf<List<String>>(emptyList()) }
    var whiteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allApps by remember { mutableStateOf<List<AppOption>>(emptyList()) }
    var picker by remember { mutableStateOf<AppListKind?>(null) }
    var timePicker by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    var requestDnd by remember { mutableStateOf(false) }
    if (requestDnd) AlertDialog(onDismissRequest = { requestDnd = false },
        title = { Text("开启免打扰") },
        text = { Text("此计划的免打扰需要系统授权。不授权时计划仍可执行，但自动勿扰不会生效。") },
        confirmButton = { TextButton(onClick = { requestDnd = false; PermissionIntent.open(context, PermissionKind.DND) }) { Text("前往系统设置") } },
        dismissButton = { TextButton(onClick = { requestDnd = false }) { Text("稍后") } },
    )

    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            resolver.installedApps().map { app -> AppOption(app.packageName, app.name, app.category, app.icon?.toBitmap(64, 64)?.asImageBitmap()) }
        }
        val extras = whiteIds.filter { saved -> installed.none { it.name == saved || it.id == saved } }
            .map { saved -> allApps.firstOrNull { it.id == saved } ?: AppOption(saved, saved, "工具") }
        allApps = installed + extras
        whiteIds = resolveSavedIds(whiteIds.toList(), allApps)
        appsLoading = false
    }

    LaunchedEffect(planId) {
        if (planId != null) {
            store.plan(planId)?.let { plan ->
                name = plan.name
                type = plan.type.takeIf { it in listOf("学习", "工作", "运动", "睡眠") } ?: "学习"
                startTime = PlanTime.format(plan.startTime)
                endTime = PlanTime.format(plan.endTime)
                reminder = plan.remindAdvanceMin
                val parsed = parseRepeat(plan.repeatRule)
                preset = parsed.first
                weekdays = parsed.second
            }
            val savedStrategy = store.strategy(planId)
            strategyDraft = PlanStrategyDraft.fromSnapshot(savedStrategy, isNewPlan = false)
            savedStrategy?.let { strategy ->
                val white = parseAppNames(strategy.whitelistApps)
                originalWhitelist = white
                val extras = white.filter { saved -> installed.none { it.name == saved || it.id == saved } }.distinct().map { AppOption(it, it, "工具") }
                allApps = installed + extras
                whiteIds = resolveSavedIds(white, allApps)
            }
            strategyLoaded = true
        }
    }

    picker?.let { kind ->
        val selected = whiteIds
        AppPickerSheet(
            title = "选择不计入分心的应用",
            apps = allApps,
            selectedIds = selected,
            lockedIds = emptySet(),
            onToggle = { app ->
                fun toggled(value: Set<String>) = if (app.id in value) value - app.id else value + app.id
                whiteIds = toggled(whiteIds)
                strategyDraft = strategyDraft.withWhitelistChanged(whiteIds != resolveSavedIds(originalWhitelist, allApps))
            },
            onQuickAdd = { selectedApps ->
                val ids = selectedApps.map { it.id }.toSet()
                whiteIds = whiteIds + ids
                strategyDraft = strategyDraft.withWhitelistChanged(whiteIds != resolveSavedIds(originalWhitelist, allApps))
            },
            onDismiss = { picker = null }
        )
    }

    timePicker?.let { target ->
        val current = if (target == "start") startTime else endTime
        val parts = current.split(":")
        PlanTimePicker(
            title = if (target == "start") "开始时间" else "结束时间",
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 21,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            crossDayHint = if (target == "end" && (PlanTime.minutesOfDay(endTime) ?: 0) <= (PlanTime.minutesOfDay(startTime) ?: 0)) "结束时间早于开始时间，将自动视为次日" else null,
            onConfirm = { hour, minute ->
                val value = "%02d:%02d".format(hour, minute)
                if (target == "start") startTime = value else endTime = value
                timePicker = null
            },
            onDismiss = { timePicker = null }
        )
    }

    Scaffold(modifier = Modifier.planLockAtmosphere(com.planlock.design.PageAtmosphere.QUIET),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { com.planlock.design.InkSnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            title = { Text(if (planId == null) "新建计划" else "编辑计划", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it; validation = null }, label = { Text("计划名称") }, trailingIcon = { Icon(Icons.Filled.Edit, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = validation != null, supportingText = validation?.let { { Text(it) } })
            }
            item { Section("类型") { PlanTypeSegmented(type) { type = it } } }
            item {
                Section("时间") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeField("开始", startTime, { timePicker = "start" }, Modifier.weight(1f))
                        TimeField("结束", endTime, { timePicker = "end" }, Modifier.weight(1f))
                    }
                    if ((PlanTime.minutesOfDay(endTime) ?: 0) <= (PlanTime.minutesOfDay(startTime) ?: 0)) Text("结束时间早于开始时间，将自动标记为次日", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            item { Section("重复") { RepeatRuleSelector(preset, weekdays, { choice -> preset = choice; weekdays = presetDays(choice) }, { day -> weekdays = if (day in weekdays) weekdays - day else weekdays + day; preset = RepeatPreset.CUSTOM }) } }
            item { Section("提醒") {
                val selectedReminder = reminder
                if (selectedReminder == null) {
                    Text("未设置", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { reminder = 15 }) { Text("设置提醒") }
                } else {
                    ReminderStepper(selectedReminder) { reminder = it }
                    TextButton(onClick = { reminder = null }) { Text("不提醒") }
                }
            } }
            item {
                Section("行为记录") {
                    AppSelectionRow("不计入分心", if (appsLoading) "正在读取已安装应用…" else "这些应用不会进入执行报告", allApps, whiteIds) { picker = AppListKind.WHITE }
                }
            }
            item {
                Section("执行设置") {
                    SettingSwitch("免打扰", if (strategyDraft.autoDnd == null) "未配置；开启后执行时仅保留闹钟" else "执行时仅保留闹钟", strategyDraft.autoDnd == true) {
                        strategyDraft = strategyDraft.withAutoDnd(it)
                        if (it && PermissionChecker.check(context)[PermissionKind.DND] != true) requestDnd = true
                    }
                    SettingSwitch("通知静默", if (strategyDraft.notifySilence == null) "未配置；开启后减少无关通知干扰" else "减少无关通知干扰", strategyDraft.notifySilence == true) { strategyDraft = strategyDraft.withNotifySilence(it) }
                }
            }
            val actionContributions = editorContributions.filterNot { it.contribution is PluginContribution.FormSection }
            if (actionContributions.isNotEmpty()) item(key = "plugin:editor-actions") {
                PluginContributionContent(actionContributions, granted,
                    onAction = { bound, action -> runContribution(bound, action) },
                    onRequest = { bound, request ->
                        PluginRuntimeRegistry.plugins.value.firstOrNull { it.manifest.id == bound.pluginId }
                            ?.let { permissionPrompt.request(it.manifest, request) }
                    }, actionsEnabled = !pluginBusy && !appsLoading)
            }
            editorContributions.forEach { bound ->
                val form = bound.contribution as? PluginContribution.FormSection
                if (form != null) item(key = "plugin:${bound.pluginId}:${form.id}") {
                    val values = remember(configRevision, draftPlanId, form, TokenStore.localDataUserId) {
                        configuration.values(TokenStore.localDataUserId, draftPlanId, bound.pluginId, form.fields)
                    }
                    PluginFormContent(form, values, pluginBusy) { field, value ->
                        if (!pluginBusy) {
                            pluginBusy = true
                            scope.launch {
                            val message = try {
                                val result = PluginRuntimeRegistry.dispatchAction(bound.pluginId,
                                    PluginActionEvent(configuration.canonicalPlanId(TokenStore.localDataUserId, draftPlanId), field.onEvent, fieldId = field.id, value = value))
                                if (result.status == PluginActionStatus.EXECUTED) {
                                    configuration.save(TokenStore.localDataUserId, draftPlanId, bound.pluginId, field, value)
                                    configRevision++
                                }
                                result.message ?: if (result.status == PluginActionStatus.EXECUTED) "配置已保存" else "配置未保存，请重试"
                            } finally { pluginBusy = false }
                            showPluginResult(message)
                            }
                        }
                    }
                }
            }
            item {
                Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), enabled = strategyLoaded, 
                    onClick = {
                        if (name.isBlank()) { validation = "请输入计划名称"; return@Button }
                        scope.launch {
                            val save = suspend {
                                sync.withSessionChange {
                                    val savedId = savePlan(planId, name.trim(), type, startTime, endTime, preset, weekdays, reminder, strategyDraft, allApps, whiteIds, store, api, draftPlanId)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { configuration.movePlan(TokenStore.localDataUserId, draftPlanId, savedId) }
                                }
                                try { sync.pushPendingRecords() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
                            }
                            val existing = planId?.let { store.plan(it) }
                            if (existing == null) {
                                save()
                                onDone()
                            } else {
                                requestOrRunPlanAction(
                                    actionGate, existing, ProtectedPlanAction.EDIT, editOperation = save,
                                    onPrompt = { pendingAction = it },
                                    onCompleted = { onDone() },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存计划") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    PresentationCard {
        Text(title, style = com.planlock.design.InkText.meta, color = com.planlock.design.InkTone.faint)
        content()
    }
}

@Composable
private fun TimeField(label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    PickerField(label, value, modifier, onClick)
}

@Composable
private fun AppSelectionRow(label: String, subtitle: String, apps: List<AppOption>, ids: Set<String>, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Apps, null); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(label, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Filled.ChevronRight, "选择")
        }
        if (ids.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(apps.filter { it.id in ids }) { AssistChip(onClick = onClick, label = { Text(it.name) }) } }
    }
}

private suspend fun savePlan(
    planId: Long?, name: String, type: String, start: String, end: String,
    preset: RepeatPreset, weekdays: Set<Int>, reminder: Int?, strategyDraft: PlanStrategyDraft,
    apps: List<AppOption>, whiteIds: Set<String>, store: RecordStore,
    api: com.planlock.app.api.PlanLockApi,
    draftId: Long,
): Long {
    val localId = planId ?: draftId
    val rule = RepeatRule(type = preset.apiType, weekdays = weekdays.sorted().takeIf { preset != RepeatPreset.DAILY })
    val ruleJson = if (preset == RepeatPreset.DAILY) "{\"type\":\"daily\"}" else "{\"type\":\"weekly\",\"weekdays\":[${weekdays.sorted().joinToString()}]}"
    val existing = store.plan(localId)
    val snapshot = existing?.copy(
        name = name,
        type = type,
        startTime = "$start:00",
        endTime = "$end:00",
        repeatRule = ruleJson,
        remindAdvanceMin = reminder
    ) ?: PlanSnapshotEntity(localId, 0, name, type, "$start:00", "$end:00", ruleJson, null, null, reminder, null, 1, 0)
    val existingStrategy = store.strategy(localId)
    val writeStrategy = strategyDraft.shouldWriteStrategy || store.hasPendingStrategyChange(localId)
    val white = apps.filter { it.id in whiteIds }
    val snapshotStrategy = if (writeStrategy) {
        val whitelistJson = if (strategyDraft.whitelistChanged || planId == null) appJson(white) else existingStrategy?.whitelistApps
        val strategyId = existingStrategy?.id ?: -(System.currentTimeMillis() + 1)
        existingStrategy?.copy(whitelistApps = whitelistJson, autoDnd = strategyDraft.autoDnd, notifySilence = strategyDraft.notifySilence)
            ?: StrategySnapshotEntity(strategyId, localId, null, whitelistJson, strategyDraft.autoDnd, strategyDraft.notifySilence, 0, strictMode = null)
    } else null
    store.updateLocalPlan(snapshot, strategyChanged = writeStrategy, strategy = snapshotStrategy)
    return store.resolvePlanId(localId)
}

private fun appJson(apps: List<AppOption>): String = apps.joinToString(prefix = "[", postfix = "]") { "{\"name\":\"${it.name.replace("\"", "\\\"")}\",\"packageName\":\"${it.id}\"}" }
private fun resolveSavedIds(values: List<String>, apps: List<AppOption>): Set<String> = values.map { saved -> apps.firstOrNull { it.id == saved || it.name == saved }?.id ?: saved }.toSet()
private fun presetDays(value: RepeatPreset): Set<Int> = when (value) { RepeatPreset.DAILY -> (1..7).toSet(); RepeatPreset.WEEKDAY -> setOf(1, 2, 3, 4, 5); RepeatPreset.WEEKEND -> setOf(6, 7); RepeatPreset.CUSTOM -> emptySet() }
private fun parseRepeat(raw: String?): Pair<RepeatPreset, Set<Int>> = when {
    raw == null || raw.contains("daily") -> RepeatPreset.DAILY to (1..7).toSet()
    raw.contains("1,2,3,4,5") || raw.contains("1, 2, 3, 4, 5") -> RepeatPreset.WEEKDAY to setOf(1, 2, 3, 4, 5)
    raw.contains("6,7") || raw.contains("6, 7") -> RepeatPreset.WEEKEND to setOf(6, 7)
    else -> RepeatPreset.CUSTOM to Regex("[1-7]").findAll(raw).map { it.value.toInt() }.toSet()
}
