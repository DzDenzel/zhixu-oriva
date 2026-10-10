package com.planlock.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.planLockPreferences
import com.planlock.app.api.WeatherSnapshot
import androidx.datastore.preferences.core.edit
import com.planlock.app.engine.ExecutionEngine
import com.planlock.app.engine.ExecutionFeedback
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.RecordStore
import com.planlock.app.record.ScheduleReminderScheduler
import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.TimelineState
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.sync.AppSync
import androidx.compose.runtime.collectAsState
import com.planlock.design.SyncRefreshHost
import com.planlock.design.StatusRegion
import com.planlock.app.security.BackupPasswordStore
import com.planlock.design.PlanCardData
import com.planlock.design.PlanVisualState
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.V2Tokens
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    onCreate: (Boolean) -> Unit,
    onOpenDetail: (Long) -> Unit,
    localHolder: TodayLocalStateHolder,
    localKey: TodayLocalKey,
) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val reminderScheduler = remember { ScheduleReminderScheduler(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = AppSync.get(context)) }
    val syncClient = remember { AppSync.get(context) }
    val syncState by syncClient.coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clock = rememberPlanClock()
    val observedLocalKey = TodayLocalKey(
        userId = com.planlock.app.api.TokenStore.userId,
        dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(clock.time),
    )
    val currentLocalKey = if (observedLocalKey == localKey) localKey else observedLocalKey
    val localState by localHolder.state.collectAsState()
    val localRead = localState.read.takeIf { localState.key == currentLocalKey }
    val localData = localRead?.data
    val plans = localData?.plans.orEmpty()
    val schedules = localData?.schedules.orEmpty()
    val loading = localData == null && localRead?.error == null
    val readError = localRead?.error
    val streak = localData?.streak ?: 0
    val violations = localData?.violations ?: 0
    val unlocks = localData?.unlocks ?: 0
    var runningId by remember { mutableStateOf(ExecutionEngine.activePlanId()) }
    val weather = localData?.weather
    val weatherFromCache = weather != null
    var showWeatherInfo by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showCreateActions by remember { mutableStateOf(false) }
    var showScheduleEditor by remember { mutableStateOf(false) }
    var savingSchedule by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduleSnapshotEntity?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var timelineExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var positionedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    PlanActionDialogHost(actionGate, pendingAction, onDismiss = { pendingAction = null })

    var handledSyncRevision by remember(localHolder, currentLocalKey) {
        mutableStateOf(
            syncState.revision.takeIf {
                localHolder.state.value.key == currentLocalKey &&
                    localHolder.state.value.phase == TodayLocalPhase.READY
            },
        )
    }
    LaunchedEffect(syncState.revision, currentLocalKey) {
        if (handledSyncRevision != syncState.revision) {
            localHolder.refresh(currentLocalKey)
            handledSyncRevision = syncState.revision
        }
        while (true) {
            delay(30_000)
            localHolder.refresh(currentLocalKey)
        }
    }
    LaunchedEffect(currentLocalKey) {
        ExecutionFeedback.events.collect {
            runningId = ExecutionEngine.activePlanId()
            localHolder.refresh(currentLocalKey)
        }
    }

    val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    val occurrenceDate = OccurrenceDate.from(clock)
    val active = plans.firstOrNull { it.id == runningId && PlanTime.isActive(it, nowMinutes, occurrenceDate) }
        ?: plans.firstOrNull { PlanTime.isActive(it, nowMinutes, occurrenceDate) }
    val todayKey = remember(clock) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.timeInMillis)) }
    val selectedKey = selectedDate ?: todayKey
    val selectedNowMinutes = if (selectedKey == todayKey) nowMinutes else 0
    val timeline = remember(plans, schedules, selectedKey, selectedNowMinutes) { DayTimeline.build(selectedKey, plans, schedules, selectedNowMinutes) }
    val window = remember(timeline, selectedNowMinutes) { DayTimeline.window(timeline, selectedNowMinutes) }
    LaunchedEffect(loading, selectedKey, timelineExpanded) {
        if (timelineExpanded && !loading && positionedDate != selectedKey && timeline.isNotEmpty()) {
            val offset = with(density) { ((nowMinutes - window.startMin) / 60f * 56f).dp.roundToPx() }
            listState.scrollToItem(4, if (selectedKey == todayKey) offset.coerceAtLeast(0) else 0)
            positionedDate = selectedKey
        }
    }

    if (showCreateActions) CreateActionSheet(
        onDismiss = { showCreateActions = false },
        onPlan = { showCreateActions = false; onCreate(plans.isEmpty()) },
        onSchedule = { showCreateActions = false; editingSchedule = null; showScheduleEditor = true },
    )
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
                        localHolder.refresh(currentLocalKey)
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
                localHolder.refresh(currentLocalKey)
            }
        } },
    )
    if (showWeatherInfo) weather?.let { snapshot ->
        WeatherInfoDialog(snapshot = snapshot, fromCache = weatherFromCache, onDismiss = { showWeatherInfo = false })
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.TODAY),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        SyncRefreshHost(syncState.running, readError ?: syncState.error, { localHolder.refresh(currentLocalKey); syncClient.coordinator.refresh() },
            Modifier.fillMaxSize().padding(padding),
            // Coordinated failures share identity across pages; local reads keep their own suffix.
            errorEventKey = "${com.planlock.app.api.TokenStore.userId}:${syncState.attempts}" +
                readError?.let { ":today-read:$it" }.orEmpty()
        ) { syncStatus ->
            if (loading) {
                UIStatePanel(UiState.LOADING)
                Box(Modifier.align(Alignment.TopEnd).padding(end = 24.dp, top = 26.dp)) { syncStatus() }
            } else {
                TodayOverviewContent(
                    syncStatus = syncStatus,
                    listState = listState,
                    model = TodayOverviewModel(
                        clock = clock,
                        plans = plans,
                        schedules = schedules,
                        streak = streak,
                        violations = violations,
                        unlocks = unlocks,
                        weather = weather,
                        weatherFromCache = weatherFromCache,
                        active = active,
                        todayKey = todayKey,
                        selectedKey = selectedKey,
                        nowMinutes = nowMinutes,
                        selectedNowMinutes = selectedNowMinutes,
                        timeline = timeline,
                        timelineExpanded = timelineExpanded,
                        actionMessage = actionMessage,
                    ),
                    actions = TodayOverviewActions(
                        onWeatherInfo = { showWeatherInfo = true },
                        onCreate = { showCreateActions = true },
                        onSelectDate = { key ->
                            selectedDate = key.takeUnless { it == todayKey }
                            timelineExpanded = false
                            positionedDate = null
                        },
                        onBackToday = { selectedDate = null; timelineExpanded = false; positionedDate = null },
                        onPause = { plan ->
                            scope.launch {
                                requestOrRunPlanAction(
                                    actionGate,
                                    plan,
                                    ProtectedPlanAction.PAUSE,
                                    onPrompt = { pendingAction = it },
                                    onCompleted = { warning -> actionMessage = warning; localHolder.refresh(currentLocalKey) },
                                )
                            }
                        },
                        onExpand = { timelineExpanded = true; positionedDate = null },
                        onCollapse = { timelineExpanded = false },
                        onOpenPlan = onOpenDetail,
                        onOpenSchedule = { id ->
                            editingSchedule = schedules.firstOrNull { it.id == id }
                            showScheduleEditor = true
                        },
                    ),
                )
            }
        }
    }
}

/** Cheap display-only projection used under the final splash fade. */
@Composable
fun TodayFirstFrame(read: TodayLocalRead, modifier: Modifier = Modifier) {
    val commitLogged = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    SideEffect {
        if (commitLogged.compareAndSet(false, true)) {
            android.util.Log.i("OrivaSplash", "first-frame-commit event=composition-commit presented=false")
        }
    }
    Scaffold(
        modifier = modifier.planLockAtmosphere(PageAtmosphere.TODAY),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val data = read.data
        if (data == null) {
            UIStatePanel(
                state = UiState.ERROR,
                message = read.error ?: "本机数据读取失败，请重试",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            val clock = rememberPlanClock()
            val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
            val occurrenceDate = OccurrenceDate.from(clock)
            val runningId = ExecutionEngine.activePlanId()
            val active = data.plans.firstOrNull { it.id == runningId && PlanTime.isActive(it, nowMinutes, occurrenceDate) }
                ?: data.plans.firstOrNull { PlanTime.isActive(it, nowMinutes, occurrenceDate) }
            val todayKey = remember(clock) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.timeInMillis)) }
            val timeline = remember(data, todayKey, nowMinutes) {
                DayTimeline.build(todayKey, data.plans, data.schedules, nowMinutes)
            }
            Box(Modifier.fillMaxSize().padding(padding)) {
                TodayOverviewContent(
                    listState = rememberLazyListState(),
                    model = TodayOverviewModel(
                        clock = clock,
                        plans = data.plans,
                        schedules = data.schedules,
                        streak = data.streak,
                        violations = data.violations,
                        unlocks = data.unlocks,
                        weather = data.weather,
                        weatherFromCache = data.weather != null,
                        active = active,
                        todayKey = todayKey,
                        selectedKey = todayKey,
                        nowMinutes = nowMinutes,
                        selectedNowMinutes = nowMinutes,
                        timeline = timeline,
                        timelineExpanded = false,
                        actionMessage = null,
                    ),
                    actions = TodayOverviewActions.EMPTY,
                )
            }
        }
    }
}

private data class TodayOverviewModel(
    val clock: Calendar,
    val plans: List<PlanSnapshotEntity>,
    val schedules: List<ScheduleSnapshotEntity>,
    val streak: Int,
    val violations: Int,
    val unlocks: Int,
    val weather: WeatherSnapshot?,
    val weatherFromCache: Boolean,
    val active: PlanSnapshotEntity?,
    val todayKey: String,
    val selectedKey: String,
    val nowMinutes: Int,
    val selectedNowMinutes: Int,
    val timeline: List<TimelineEntry>,
    val timelineExpanded: Boolean,
    val actionMessage: String?,
)

private data class TodayOverviewActions(
    val onWeatherInfo: () -> Unit,
    val onCreate: () -> Unit,
    val onSelectDate: (String) -> Unit,
    val onBackToday: () -> Unit,
    val onPause: (PlanSnapshotEntity) -> Unit,
    val onExpand: () -> Unit,
    val onCollapse: () -> Unit,
    val onOpenPlan: (Long) -> Unit,
    val onOpenSchedule: (Long) -> Unit,
) {
    companion object {
        val EMPTY = TodayOverviewActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Composable
private fun TodayOverviewContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    model: TodayOverviewModel,
    actions: TodayOverviewActions,
    syncStatus: @Composable () -> Unit = { StatusRegion {} },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 28.dp),
    ) {
        item {
            TodayMasthead(
                date = formatCurrentDate(model.clock),
                streak = model.streak,
                scheduleCount = model.timeline.size,
                activeCount = if (model.active == null) 0 else 1,
                weather = model.weather,
                weatherFromCache = model.weatherFromCache,
                onWeatherInfo = actions.onWeatherInfo,
                onCreate = actions.onCreate,
                syncStatus = syncStatus,
            )
        }
        item {
            WeekIndicator(model.todayKey, model.selectedKey, model.plans, model.schedules, model.nowMinutes, actions.onSelectDate)
        }
        item {
            Column {
                if (model.selectedKey != model.todayKey) {
                    TextButton(onClick = actions.onBackToday) { Text("回到今天") }
                }
                model.active?.let { plan -> ActionCover(plan, model.nowMinutes) { actions.onPause(plan) } }
            }
        }
        if (!model.timelineExpanded) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (model.selectedKey == model.todayKey) "接下来" else "当日安排", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = actions.onExpand) { Text("查看今日全部") }
                }
            }
            if (model.timeline.isEmpty()) item { DayEmptyCover(model.selectedKey == model.todayKey, actions.onCreate) }
            itemsIndexed(model.timeline.take(3), key = { _, entry -> "compact-${entry.kind}-${entry.id}-${entry.startMin}" }) { index, entry ->
                CompactTimelineRow(
                    entry = entry,
                    modifier = Modifier.padding(bottom = compactTimelineBottomSpacing(index, model.timeline.take(3).size).dp),
                ) {
                    if (entry.kind == TimelineKind.PLAN) actions.onOpenPlan(entry.id) else actions.onOpenSchedule(entry.id)
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("完整时间轴", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = actions.onCollapse) { Text("收起") }
                }
            }
            item(key = "timeline-canvas") {
                if (model.timeline.isEmpty()) DayEmptyCover(model.selectedKey == model.todayKey, actions.onCreate)
                else TimelineCanvas(model.timeline, model.selectedNowMinutes, actions.onOpenPlan, actions.onOpenSchedule)
            }
        }
        item { StatsStrip(model.streak, model.violations, model.unlocks) }
        model.actionMessage?.let { message ->
            item { Text(message, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

@Composable
private fun WeekIndicator(todayKey: String, selectedKey: String, plans: List<PlanSnapshotEntity>, schedules: List<ScheduleSnapshotEntity>, nowMinutes: Int, onSelect: (String) -> Unit) {
    val today = remember(todayKey) { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(todayKey) }
    val start = remember(today) {
        Calendar.getInstance().apply {
            time = today ?: Date()
            add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
    }
    val days = remember(todayKey, plans, schedules, nowMinutes, start) {
        (0..6).map { dayIndex ->
            val day = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, dayIndex) }
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)
            WeekIndicatorDay(
                label = listOf("一", "二", "三", "四", "五", "六", "日")[dayIndex],
                dayOfMonth = day.get(Calendar.DAY_OF_MONTH),
                dateKey = key,
                count = DayTimeline.build(key, plans, schedules, if (key == todayKey) nowMinutes else 0).size,
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.label, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    onClick = { onSelect(day.dateKey) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (day.dateKey == selectedKey) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    border = if (day.dateKey == todayKey && day.dateKey != selectedKey) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Text("${day.dayOfMonth}", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = if (day.dateKey == selectedKey) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
                Text(if (day.count > 0) "•".repeat(day.count.coerceAtMost(3)) else "", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private data class WeekIndicatorDay(
    val label: String,
    val dayOfMonth: Int,
    val dateKey: String,
    val count: Int,
)

@Composable
private fun TodayMasthead(
    date: String,
    streak: Int,
    scheduleCount: Int,
    activeCount: Int,
    weather: WeatherSnapshot?,
    weatherFromCache: Boolean,
    onWeatherInfo: () -> Unit,
    onCreate: () -> Unit,
    syncStatus: @Composable () -> Unit = { StatusRegion {} },
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("知序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        syncStatus()
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onCreate, shape = RoundedCornerShape(V2Tokens.shapePill), color = MaterialTheme.colorScheme.surfaceContainer) {
            Icon(Icons.Filled.Add, "新建安排", Modifier.padding(8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
        Text(date, Modifier.padding(start = 10.dp), style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
        Text("把今天\n握在手里。", Modifier.weight(1f), style = V2Tokens.headlineMedium.copy(fontSize = 40.sp, lineHeight = 42.sp), fontWeight = FontWeight.Black)
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(158.dp).padding(bottom = 4.dp)) {
            weather?.let { snapshot ->
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onWeatherInfo).padding(vertical = 2.dp),
                ) {
                    Text(snapshot.location.displayName, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${"%.1f".format(Locale.US, snapshot.temperatureC)}°C · ${weatherLabel(snapshot.weatherCode)}  ⓘ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (weatherFromCache) Text("缓存 · ${snapshot.observedAt.takeLast(5)}", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
            Text("$scheduleCount 项安排", style = V2Tokens.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (activeCount == 1) "1 项正在执行" else "当前节奏平稳", style = V2Tokens.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (streak > 0) Text("连续 $streak 天", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeatherInfoDialog(snapshot: WeatherSnapshot, fromCache: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("天气数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snapshot.location.displayName, style = MaterialTheme.typography.titleMedium)
                Text("观测时间 · ${snapshot.observedAt.takeLast(5)}")
                if (fromCache) Text("当前显示上次成功获取的数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("© Open-Meteo.com", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("CC BY 4.0", style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun ActionCover(plan: PlanSnapshotEntity, nowMinutes: Int, onPause: () -> Unit) {
    val shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp)
    val remaining = PlanTime.remainingMinutes(plan, nowMinutes)
    Box(
        Modifier.fillMaxWidth().height(236.dp)
            .shadow(V2Tokens.brandE3.y, shape, ambientColor = V2Tokens.brandGlow, spotColor = V2Tokens.brandGlow)
            .clip(shape)
            .background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep)))
            .drawBehind {
                val center = Offset(size.width * .9f, size.height * .12f)
                drawCircle(V2Tokens.onBrand.copy(alpha = .13f), size.width * .34f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                drawCircle(V2Tokens.onBrand.copy(alpha = .1f), size.width * .24f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }.padding(22.dp)
    ) {
        Column {
            Text("NOW / 正在执行", style = V2Tokens.labelMicro, color = V2Tokens.onBrand.copy(alpha = .78f), fontWeight = FontWeight.Bold)
            Text(plan.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleLarge, color = V2Tokens.onBrand)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text("%02d:%02d".format(remaining / 60, remaining % 60), style = V2Tokens.numeralHero, color = V2Tokens.onBrand)
                Text("剩余", Modifier.padding(start = 9.dp, bottom = 7.dp), style = V2Tokens.bodyMedium, color = V2Tokens.onBrand.copy(alpha = .76f))
            }
            Spacer(Modifier.weight(1f))
            Text("${PlanTime.format(plan.startTime)} — ${PlanTime.format(plan.endTime)}", style = V2Tokens.labelMedium, color = V2Tokens.onBrand.copy(alpha = .9f))
            Text("${plan.type} · ${repeatLabel(plan.repeatRule)}", style = V2Tokens.labelMicro, color = V2Tokens.onBrand.copy(alpha = .72f))
        }
        Surface(onClick = onPause, modifier = Modifier.align(Alignment.BottomEnd).size(48.dp), shape = RoundedCornerShape(V2Tokens.shapePill), color = V2Tokens.brandContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Pause, "提前结束计划", tint = V2Tokens.onBrandContainer) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(V2Tokens.onBrand.copy(alpha = .2f))) {
            Box(Modifier.fillMaxWidth(PlanTime.progress(plan, nowMinutes)).height(3.dp).background(V2Tokens.brandContainer))
        }
    }
}

@Composable
private fun CompactTimelineRow(entry: TimelineEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DayTimeline.rangeLabel(entry), style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(timelineStateLabel(entry.state), style = V2Tokens.labelMedium, color = if (entry.state == TimelineState.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun compactTimelineBottomSpacing(index: Int, itemCount: Int): Int =
    if (index >= 0 && index < itemCount - 1) 8 else 0

@Composable
private fun DayEmptyCover(isToday: Boolean, onCreate: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isToday) "从一段专注开始" else "这一天还没有安排", style = MaterialTheme.typography.headlineMedium)
            Text(if (isToday) "建立第一项计划，今天的行动与节奏会在这里出现。" else "可以创建日程或计划，为这一天留下一段清晰节奏。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCreate) { Text("新建安排") }
        }
    }
}


@Composable
private fun StatsStrip(streak: Int, violations: Int, unlocks: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        shape = RoundedCornerShape(V2Tokens.shapeMd),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Metric("$streak", "连续达成", Modifier.weight(1f))
            Metric("$violations", "今日违规", Modifier.weight(1f), true)
            Metric("$unlocks", "今日解锁", Modifier.weight(1f), true)
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier, divider: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (divider) Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = V2Tokens.labelMicro, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun PlanSnapshotEntity.toCardData(state: PlanVisualState? = null) = PlanCardData(
    id = id,
    name = name,
    type = type,
    timeText = "${PlanTime.format(startTime)} 至 ${PlanTime.format(endTime)}",
    repeatText = repeatLabel(repeatRule),
    state = state ?: if (status == 1) PlanVisualState.UPCOMING else PlanVisualState.PAUSED,
)

internal fun repeatLabel(raw: String?): String = when {
    raw == null || raw.contains("daily") -> "每天"
    raw.contains("1,2,3,4,5") || raw.contains("1, 2, 3, 4, 5") -> "工作日"
    raw.contains("6,7") || raw.contains("6, 7") -> "周末"
    else -> "自定义"
}

private fun formatCurrentDate(clock: Calendar): String {
    val week = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[clock.get(Calendar.DAY_OF_WEEK) - 1]
    return "${clock.get(Calendar.MONTH) + 1} 月 ${clock.get(Calendar.DAY_OF_MONTH)} 日 · $week"
}

private fun timelineStateLabel(state: TimelineState): String = when (state) {
    TimelineState.RUNNING -> "进行中"
    TimelineState.UPCOMING -> "待开始"
    TimelineState.ENDED -> "已结束"
    TimelineState.PAUSED -> "已暂停"
}

private fun weatherLabel(code: Int): String = when (code) {
    0 -> "晴"
    1, 2 -> "少云"
    3 -> "阴"
    45, 48 -> "雾"
    in 51..67 -> "雨"
    in 71..77 -> "雪"
    in 80..82 -> "阵雨"
    in 85..86 -> "阵雪"
    in 95..99 -> "雷雨"
    else -> "天气"
}
