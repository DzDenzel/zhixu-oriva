package com.planlock.feature.plan

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.RecordStore
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.ScheduleReminderScheduler
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.app.sync.AppSync
import androidx.compose.runtime.collectAsState
import com.planlock.design.SectionHeading
import com.planlock.design.SyncRefreshHost
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.planlock.design.LocalReducedMotion
import com.planlock.design.PageAtmosphere
import com.planlock.design.PlanCard
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.planLockAtmosphere
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PlanShelfMode { PLANS, SCHEDULES }
private data class ScheduleOccurrence(val date: String, val schedule: ScheduleSnapshotEntity)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onCreate: (Boolean) -> Unit = {}, onOpenDetail: (Long) -> Unit = {}) {
    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val reminderScheduler = remember { ScheduleReminderScheduler(context) }
    val syncClient = remember { AppSync.get(context) }
    val syncState by syncClient.coordinator.state.collectAsState()
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = syncClient) }
    val scope = rememberCoroutineScope()
    val clock = rememberPlanClock()
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<ScheduleSnapshotEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    var allExpanded by rememberSaveable { mutableStateOf(false) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var shelfMode by rememberSaveable { mutableStateOf(PlanShelfMode.PLANS) }
    var editingSchedule by remember { mutableStateOf<ScheduleSnapshotEntity?>(null) }
    var showScheduleEditor by remember { mutableStateOf(false) }
    var savingSchedule by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scheduleListState = rememberLazyListState()

    PlanActionDialogHost(actionGate, pendingAction) { pendingAction = null }

    suspend fun refresh() {
        error = null
        try {
            plans = store.plans()
            schedules = store.schedules()
            schedules.forEach(reminderScheduler::schedule)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "本机数据读取失败，请重试"
        }
        loading = false
    }
    LaunchedEffect(syncState.revision) { refresh() }

    val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    val today = OccurrenceDate.from(clock)
    val overview = remember(plans, nowMinutes, today) { PlanOverview.build(plans, nowMinutes, today) }
    val filteredAll = remember(overview.scheduled, selectedCategory) {
        overview.scheduled.filter { selectedCategory == null || it.type.trim().ifEmpty { "其他" } == selectedCategory }
    }
    val scheduleOccurrences = remember(schedules) { upcomingScheduleOccurrences(schedules) }

    if (showScheduleEditor) ScheduleEditorSheet(
        current = editingSchedule,
        plans = plans,
        onDismiss = { showScheduleEditor = false },
        saving = savingSchedule,
        onSave = { request ->
            if (!savingSchedule) {
                savingSchedule = true
                scope.launch {
                    try {
                        saveSchedule(store, reminderScheduler, syncClient, request, editingSchedule)
                        showScheduleEditor = false
                        refresh()
                    } finally {
                        savingSchedule = false
                    }
                }
            }
        },
        onDelete = editingSchedule?.let { old -> {
            scope.launch {
                store.enqueueScheduleDelete(old.id)
                reminderScheduler.cancel(old.id)
                runCatching { syncClient.pushPendingRecords() }
                showScheduleEditor = false
                refresh()
            }
        } },
    )

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.PLANS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (shelfMode == PlanShelfMode.PLANS) onCreate(plans.isEmpty())
                else { editingSchedule = null; showScheduleEditor = true }
            }) { Icon(Icons.Filled.Add, if (shelfMode == PlanShelfMode.PLANS) "新建计划" else "新建日程") }
        },
    ) { padding ->
        SyncRefreshHost(syncState.running, syncState.error, { syncClient.coordinator.refresh() }, Modifier.padding(padding),
            errorEventKey = "${com.planlock.app.api.TokenStore.userId}:${syncState.attempts}") { syncStatus ->
        Column(Modifier.fillMaxSize()) {
            PlanShelfHeader(shelfMode, onChange = { shelfMode = it }, onRefresh = { syncClient.coordinator.refresh() }, syncStatus = syncStatus)
            when {
            loading && plans.isEmpty() && schedules.isEmpty() -> UIStatePanel(UiState.LOADING, modifier = Modifier.weight(1f))
            error != null && plans.isEmpty() && schedules.isEmpty() -> UIStatePanel(UiState.ERROR, error, { scope.launch { refresh() } }, Modifier.weight(1f).verticalScroll(rememberScrollState()))
            shelfMode == PlanShelfMode.SCHEDULES -> androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                ScheduleList(
                    occurrences = scheduleOccurrences,
                    deliveryMode = reminderScheduler.deliveryMode().name,
                    state = scheduleListState,
                    onCreate = { editingSchedule = null; showScheduleEditor = true },
                    onOpen = { editingSchedule = it; showScheduleEditor = true },
                )
            }
            plans.isEmpty() -> UIStatePanel(UiState.EMPTY, "建立第一项计划", { onCreate(true) }, Modifier.weight(1f).verticalScroll(rememberScrollState()))
            else -> androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { allExpanded = !allExpanded }
                                .semantics { contentDescription = if (allExpanded) "全部计划，已展开" else "全部计划，已收起" },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("全部计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("共 ${plans.distinctBy { it.id }.size} 项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (allExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (allExpanded) "收起" else "展开")
                            }
                        }
                    }

                    if (!allExpanded) {
                        item { SectionTitle("当前执行", "${overview.current.size} 项") }
                        if (overview.current.isEmpty()) item { QuietText("当前没有正在执行的计划") }
                        items(overview.current, key = { "current-${it.id}" }) { plan ->
                            PlanCard(
                                plan.toCardData(PlanVisualState.EXECUTING),
                                onClick = { onOpenDetail(plan.id) },
                                modifier = Modifier.animateItem(tween(if (reducedMotion) 0 else 180), tween(if (reducedMotion) 0 else 220), tween(if (reducedMotion) 0 else 180)),
                                sharedKey = "plan-${plan.id}",
                                trailingAction = {
                                    TextButton(onClick = {
                                        scope.launch {
                                            requestOrRunPlanAction(actionGate, plan, ProtectedPlanAction.PAUSE,
                                                onPrompt = { pendingAction = it },
                                                onCompleted = { executionHaptic(context); scope.launch { refresh() } })
                                        }
                                    }) { Text("提前结束") }
                                },
                                onOverflow = { onOpenDetail(plan.id) },
                            )
                        }

                        item { SectionTitle("近期计划", "未来 7 天 · ${overview.upcoming.size} 项") }
                        if (overview.upcoming.isEmpty()) item { QuietText("未来七天没有待开始计划") }
                        items(overview.upcoming, key = { "upcoming-${it.plan.id}" }) { occurrence ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(nextLabel(occurrence.dayOffset), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                PlanCard(occurrence.plan.toCardData(PlanVisualState.UPCOMING), onClick = { onOpenDetail(occurrence.plan.id) }, onOverflow = { onOpenDetail(occurrence.plan.id) })
                            }
                        }

                        if (overview.categoryCounts.isNotEmpty()) {
                            item { SectionTitle("按类型筛选", "快速定位计划") }
                            overview.categoryCounts.entries.chunked(2).forEachIndexed { rowIndex, row ->
                                item(key = "category-row-$rowIndex") {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { (category, count) -> CategoryTile(category, count, Modifier.weight(1f)) {
                                            selectedCategory = category
                                            allExpanded = true
                                        } }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        item { SectionTitle("完整列表", "${filteredAll.size} 项") }
                        item { CategoryFilter("全部", selectedCategory == null, Modifier.fillMaxWidth()) { selectedCategory = null } }
                        overview.categoryCounts.keys.chunked(3).forEachIndexed { rowIndex, row ->
                            item(key = "filter-row-$rowIndex") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { category ->
                                        CategoryFilter(category, selectedCategory == category, Modifier.weight(1f)) { selectedCategory = category }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        items(filteredAll, key = { "all-${it.id}" }) { plan ->
                            PlanCard(plan.toCardData(if (plan in overview.current) PlanVisualState.EXECUTING else PlanVisualState.UPCOMING), onClick = { onOpenDetail(plan.id) }, onOverflow = { onOpenDetail(plan.id) })
                        }
                        item {
                            Surface(
                                Modifier.fillMaxWidth().semantics { contentDescription = if (storageExpanded) "暂停计划，已展开" else "暂停计划，已收起" }
                                    .clickable { storageExpanded = !storageExpanded },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("已暂停 / 时间不完整", style = MaterialTheme.typography.titleMedium)
                                        Text("${overview.stored.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(if (storageExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (storageExpanded) "收起" else "展开")
                                }
                            }
                        }
                        if (storageExpanded) items(overview.stored, key = { "stored-${it.id}" }) { plan ->
                            PlanCard(plan.toCardData(PlanVisualState.PAUSED), onClick = { onOpenDetail(plan.id) }, onOverflow = { onOpenDetail(plan.id) })
                        }
                    }
                    item { Spacer(Modifier.padding(bottom = 48.dp)) }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun PlanShelfHeader(mode: PlanShelfMode, onChange: (PlanShelfMode) -> Unit, onRefresh: () -> Unit, syncStatus: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 360.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("日程与计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        syncStatus()
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "刷新") }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("日程与计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f))
                    syncStatus()
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "刷新") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PlanShelfMode.PLANS to "计划", PlanShelfMode.SCHEDULES to "日程").forEach { (value, label) ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { onChange(value) },
                    shape = MaterialTheme.shapes.large,
                    color = if (mode == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) { Text(label, Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ScheduleList(
    occurrences: List<ScheduleOccurrence>,
    deliveryMode: String,
    state: androidx.compose.foundation.lazy.LazyListState,
    onCreate: () -> Unit,
    onOpen: (ScheduleSnapshotEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        state = state,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                if (deliveryMode == "EXACT") "提醒模式：精确" else "提醒模式：非精确（系统可能轻微延迟）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (occurrences.isEmpty()) item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                UIStatePanel(state = UiState.EMPTY, message = "开始安排你的第一项日程", emptySubject = "日程")
                androidx.compose.material3.OutlinedButton(onClick = onCreate,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("创建第一个日程") }
            }
        }
        items(occurrences, key = { "${it.date}-${it.schedule.id}" }) { occurrence ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(occurrence.schedule) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(occurrence.date.substring(5), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(occurrence.schedule.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (occurrence.schedule.allDay) "全天" else occurrence.schedule.startTime?.take(5).orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        occurrence.schedule.location?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 48.dp)) }
    }
}

private fun upcomingScheduleOccurrences(schedules: List<ScheduleSnapshotEntity>): List<ScheduleOccurrence> {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val day = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return buildList {
        repeat(366) {
            val key = format.format(day.time)
            DayTimeline.build(key, emptyList(), schedules, 0, collapseOverlaps = false)
                .filter { it.kind == com.planlock.app.record.TimelineKind.SCHEDULE }
                .forEach { entry -> schedules.firstOrNull { it.id == entry.id }?.let { add(ScheduleOccurrence(key, it)) } }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
    }.take(200)
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    SectionHeading(title, detail)
}

@Composable
private fun QuietText(text: String) {
    Text(text, modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryTile(name: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$count 项计划", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryFilter(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun nextLabel(offset: Int): String = when (offset) {
    0 -> "今天"
    1 -> "明天"
    else -> "$offset 天后"
}
