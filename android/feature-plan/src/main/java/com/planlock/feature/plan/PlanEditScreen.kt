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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.planlock.design.inkFormField
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
import com.planlock.design.color
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlanEditScreen(planId: Long?, onDone: () -> Unit = {}, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val editorScope = remember(planId) { store.currentScope() }
    val sync = remember { com.planlock.app.sync.AppSync.get(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = sync) }
    val resolver = remember { AppPackageResolver(context) }
    val scope = rememberCoroutineScope()
    val punctualStart by remember(context) { com.planlock.app.api.PlanTimingPreferences.enabled(context.planLockPreferences) }.collectAsState(initial = true)
    val loadedPlugins by PluginRuntimeRegistry.plugins.collectAsState()
    val authState by TokenStore.state.collectAsState()
    LaunchedEffect(authState.dataScope) { if (authState.dataScope != editorScope) onBack() }
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
        if (editorScope != store.currentScope()) return
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
                            PluginActionEvent(saved?.id ?: configuration.canonicalPlanId(editorScope, draftPlanId), action.actionId, app?.id, app?.name, until))
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
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    val capabilityGate = com.planlock.design.rememberCapabilityPermissionGate()
    val typeCatalog = remember(store, sync, scope) { PlanTypeCatalog(store, sync, scope) }
    val typeCatalogState by typeCatalog.state.collectAsState()
    // v4.5（U-c）：自定义类型面板；新建后自动选中新类型（目录刷新后按名称找到它的键）
    var typeEditor by remember { mutableStateOf<TypeEditorTarget?>(null) }
    var pendingCustomName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(typeCatalogState.types, pendingCustomName) {
        val wanted = pendingCustomName ?: return@LaunchedEffect
        typeCatalogState.types.lastOrNull { it.key.startsWith("custom:") && it.name == wanted }?.let { type = it.key; pendingCustomName = null }
    }
    typeEditor?.let { target ->
        val editing = (target as? TypeEditorTarget.Edit)?.type
        com.planlock.design.PlanTypeEditorSheet(
            initialName = editing?.name.orEmpty(),
            initialColor = editing?.color ?: com.planlock.app.api.PlanTypes.colors.first(),
            colorKeys = com.planlock.app.api.PlanTypes.colors.toList(),
            existingNames = typeCatalogState.types.filter { it.key != editing?.key }.map { it.name },
            usage = editing?.let { typeCatalogState.usageCounts[it.key] ?: 0 },
            onSave = { name, color ->
                if (editing == null) { typeCatalog.create(name, color); pendingCustomName = name }
                else typeCatalog.rename(editing.key, name, color)
                typeEditor = null
            },
            onDelete = if (editing == null) null else ({
                typeCatalog.delete(editing.key, confirmed = true)
                if (type == editing.key) type = "其他"
                typeEditor = null
            }),
            onDismiss = { typeEditor = null },
        )
    }
    var deniedCapabilities by remember { mutableStateOf(emptySet<com.planlock.app.security.Capability>()) }

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
                type = plan.type
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

    // BUG-112（v4.4）：保存栏贴底、随输入法上移；点保存先校验，不通过不弹窗——直接滚到第一项并在保存栏上方提醒。
    val targets = com.planlock.design.rememberInkFormTargets()
    var attempted by remember { mutableStateOf(false) }
    val issues = planFormIssues(name, preset, weekdays)
    val shownIssues = if (attempted) issues else emptyList()
    val revealFirst: () -> Unit = { issues.firstOrNull()?.let { scope.launch { targets.reveal(it.field) } } }
    val onSave: () -> Unit = save@{
        attempted = true
        if (issues.isNotEmpty()) { revealFirst(); return@save }
        capabilityGate.beforeSave(com.planlock.app.security.CapabilityRequirements.plan(strategyDraft.autoDnd, strategyDraft.notifySilence, reminder != null, punctualStart)) { scope.launch {
            val save = suspend {
                sync.withLocalMutation {
                    check(editorScope == store.currentScope()) { "账号已变化，请重新打开编辑页" }
                    val savedId = savePlan(planId, name.trim(), type, startTime, endTime, preset, weekdays, reminder, strategyDraft, allApps, whiteIds, store, api, draftPlanId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { configuration.movePlan(editorScope, draftPlanId, savedId) }
                }
                sync.localChangesCommitted()
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
        } }
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
    }, bottomBar = { com.planlock.design.InkSaveBar("保存计划", onSave, enabled = strategyLoaded,
        hint = com.planlock.design.saveBarHint(shownIssues), onHint = revealFirst) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("计划名称") }, trailingIcon = { Icon(Icons.Filled.Edit, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().inkFormField(targets, PlanField.NAME, focusable = true, cornerRadius = 6.dp),
                isError = shownIssues.messageFor(PlanField.NAME) != null, supportingText = shownIssues.messageFor(PlanField.NAME)?.let { { Text(it) } })
            Section("类型") {
                // v4.4（U6）：带类别色的类型网格，一行 4 格；颜色取自类型目录（自定义类型用它自己选的颜色）。
                val typeOptions = if (typeCatalogState.dataScope == editorScope) typeCatalogState.types else com.planlock.app.api.PlanTypes.presets
                val selectedTypeKey = typeOptions.firstOrNull { it.key == type }?.key
                    ?: if (!type.startsWith("custom:")) com.planlock.app.api.PlanTypes.definition(type).key else null
                com.planlock.design.PlanTypeGrid(
                    typeOptions.map { com.planlock.design.PlanTypeChoice(it.key, it.name, it.color) },
                    selectedTypeKey,
                    { type = it },
                    onAddCustom = { typeEditor = TypeEditorTarget.New },
                    onEdit = { key -> typeOptions.firstOrNull { it.key == key }?.let { typeEditor = TypeEditorTarget.Edit(it) } },
                )
                // v4.5（U-c）：自定义类型长按可编辑；目录读写失败时在这里说一句
                if (typeOptions.any { it.key.startsWith("custom:") })
                    Text("长按自定义类型可改名、换色或删除", style = com.planlock.design.InkText.caption, color = com.planlock.design.InkTone.soft)
                typeCatalogState.error?.let { Text(it, style = com.planlock.design.InkText.caption, color = MaterialTheme.colorScheme.error) }
            }
            Section("时间") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField("开始", startTime, { timePicker = "start" }, Modifier.weight(1f))
                    TimeField("结束", endTime, { timePicker = "end" }, Modifier.weight(1f))
                }
                if ((PlanTime.minutesOfDay(endTime) ?: 0) <= (PlanTime.minutesOfDay(startTime) ?: 0)) Text("结束时间早于开始时间，将自动标记为次日", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Section("重复", Modifier.inkFormField(targets, PlanField.REPEAT, cornerRadius = 18.dp)) {
                RepeatRuleSelector(preset, weekdays, { choice -> preset = choice; weekdays = presetDays(choice) }, { day -> weekdays = if (day in weekdays) weekdays - day else weekdays + day; preset = RepeatPreset.CUSTOM })
                shownIssues.messageFor(PlanField.REPEAT)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            Section("提醒") {
                val selectedReminder = reminder
                if (selectedReminder == null) {
                    Text("未设置", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { reminder = 15 }) { Text("设置提醒") }
                } else {
                    ReminderStepper(selectedReminder) { reminder = it }
                    TextButton(onClick = { reminder = null }) { Text("不提醒") }
                    // BUG-125（v4.6）：已进入本次提醒窗口时，保存前就说明今天这次不再提醒
                    // Sample just after the trigger boundary, rather than keeping exact equality for a minute.
                    val clockNow = rememberPlanClock(boundaryOffsetMillis = 1000L)
                    val isoDay = (clockNow.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
                    reminderWindowNote(startTime, selectedReminder, weekdays,
                        clockNow.get(java.util.Calendar.HOUR_OF_DAY) * 60 + clockNow.get(java.util.Calendar.MINUTE), isoDay,
                        clockNow.get(java.util.Calendar.SECOND) * 1000 + clockNow.get(java.util.Calendar.MILLISECOND))?.let { note ->
                        Text(note, style = com.planlock.design.InkText.caption, color = com.planlock.design.InkHue.amber.color())
                    }
                }
            }
            Section("行为记录") {
                AppSelectionRow("不计入分心", if (appsLoading) "正在读取已安装应用…" else "这些应用不会进入执行报告", allApps, whiteIds) { picker = AppListKind.WHITE }
            }
            Section("执行设置") {
                SettingSwitch("免打扰", if (strategyDraft.autoDnd == null) "未配置；开启后执行时仅保留闹钟" else "执行时仅保留闹钟", strategyDraft.autoDnd == true) {
                    strategyDraft = strategyDraft.withAutoDnd(it)
                    if (it) capabilityGate.request(com.planlock.app.security.Capability.DND) { granted ->
                        strategyDraft = strategyDraft.withAutoDnd(granted)
                        deniedCapabilities = if (granted) deniedCapabilities - com.planlock.app.security.Capability.DND else deniedCapabilities + com.planlock.app.security.Capability.DND
                    }
                }
                // v4.5（U-b）：琥珀小字，点它重新打开说明面板
                if (com.planlock.app.security.Capability.DND in deniedCapabilities) com.planlock.design.CapabilityDeniedNote(onClick = {
                    capabilityGate.request(com.planlock.app.security.Capability.DND) { granted ->
                        strategyDraft = strategyDraft.withAutoDnd(granted)
                        deniedCapabilities = if (granted) deniedCapabilities - com.planlock.app.security.Capability.DND else deniedCapabilities + com.planlock.app.security.Capability.DND
                    }
                })
                SettingSwitch("通知静默", if (strategyDraft.notifySilence == null) "未配置；开启后减少无关通知干扰" else "减少无关通知干扰", strategyDraft.notifySilence == true) {
                    strategyDraft = strategyDraft.withNotifySilence(it)
                    if (it) capabilityGate.request(com.planlock.app.security.Capability.NOTIFICATION_SILENCE) { granted ->
                        strategyDraft = strategyDraft.withNotifySilence(granted)
                        deniedCapabilities = if (granted) deniedCapabilities - com.planlock.app.security.Capability.NOTIFICATION_SILENCE else deniedCapabilities + com.planlock.app.security.Capability.NOTIFICATION_SILENCE
                    }
                }
                // v4.5（U-b）：琥珀小字，点它重新打开说明面板
                if (com.planlock.app.security.Capability.NOTIFICATION_SILENCE in deniedCapabilities) com.planlock.design.CapabilityDeniedNote(onClick = {
                    capabilityGate.request(com.planlock.app.security.Capability.NOTIFICATION_SILENCE) { granted ->
                        strategyDraft = strategyDraft.withNotifySilence(granted)
                        deniedCapabilities = if (granted) deniedCapabilities - com.planlock.app.security.Capability.NOTIFICATION_SILENCE else deniedCapabilities + com.planlock.app.security.Capability.NOTIFICATION_SILENCE
                    }
                })
            }
            val actionContributions = editorContributions.filterNot { it.contribution is PluginContribution.FormSection }
            if (actionContributions.isNotEmpty()) androidx.compose.runtime.key("plugin:editor-actions") {
                PluginContributionContent(actionContributions, granted,
                    onAction = { bound, action -> runContribution(bound, action) },
                    onRequest = { bound, request ->
                        PluginRuntimeRegistry.plugins.value.firstOrNull { it.manifest.id == bound.pluginId }
                            ?.let { permissionPrompt.request(it.manifest, request) }
                    }, actionsEnabled = !pluginBusy && !appsLoading)
            }
            editorContributions.forEach { bound ->
                val form = bound.contribution as? PluginContribution.FormSection
                if (form != null) androidx.compose.runtime.key("plugin:${bound.pluginId}:${form.id}") {
                    val values = remember(configRevision, draftPlanId, form, authState.dataScope) {
                        if (authState.dataScope == editorScope) configuration.values(editorScope, draftPlanId, bound.pluginId, form.fields) else emptyMap()
                    }
                    PluginFormContent(form, values, pluginBusy) { field, value ->
                        if (!pluginBusy) {
                            pluginBusy = true
                            scope.launch {
                            val message = try {
                                check(editorScope == store.currentScope()) { "账号已变化，请重新打开编辑页" }
                                val result = PluginRuntimeRegistry.dispatchAction(bound.pluginId,
                                    PluginActionEvent(configuration.canonicalPlanId(editorScope, draftPlanId), field.onEvent, fieldId = field.id, value = value))
                                if (result.status == PluginActionStatus.EXECUTED) {
                                    check(editorScope == store.currentScope()) { "账号已变化，请重新打开编辑页" }
                                    configuration.save(editorScope, draftPlanId, bound.pluginId, field, value)
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
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PresentationCard(modifier) {
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

/** 自定义类型面板的打开方式：新建，或编辑某个已有的自定义类型。 */
private sealed interface TypeEditorTarget {
    object New : TypeEditorTarget
    data class Edit(val type: com.planlock.app.api.PlanTypeDefinition) : TypeEditorTarget
}
