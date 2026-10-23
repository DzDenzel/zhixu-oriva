package com.planlock.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import com.planlock.design.DialArc
import com.planlock.design.AssistantMark
import com.planlock.design.AssistantIdentity
import com.planlock.design.InkFab
import com.planlock.design.inkTint
import com.planlock.design.tint
import com.planlock.design.color
import com.planlock.design.InkHue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Today
import com.planlock.design.LocalReducedMotion
import com.planlock.design.InkRoundAction
import com.planlock.design.DayRibbon
import com.planlock.design.EnsoMark
import com.planlock.design.Ink
import com.planlock.design.InkMasthead
import com.planlock.design.InkPulseDot
import com.planlock.design.InkRing
import com.planlock.design.InkRule
import com.planlock.design.InkSeal
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.themed
import com.planlock.design.LocalDarkTheme
import com.planlock.design.SundialDial
import com.planlock.design.inkReveal
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
import androidx.compose.animation.togetherWith
import com.planlock.design.inkPressClick
import com.planlock.design.inkPressScale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    onCreate: (Boolean) -> Unit,
    onOpenDetail: (Long) -> Unit,
    localHolder: TodayLocalStateHolder,
    localKey: TodayLocalKey,
    onAsk: () -> Unit = {},
) {
    val context = LocalContext.current
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val reminderScheduler = remember { ScheduleReminderScheduler(context) }
    val actionGate = remember { PlanActionGate(store, api, BackupPasswordStore(context.planLockPreferences), context.planLockPreferences, sync = AppSync.get(context)) }
    val syncClient = remember { AppSync.get(context) }
    val syncState by syncClient.coordinator.state.collectAsState()
    val authState by com.planlock.app.api.TokenStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clock = rememberPlanClock()
    val observedLocalKey = TodayLocalKey(
        userId = authState.localDataUserId,
        scope = authState.dataScope,
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
                    (localHolder.state.value.phase == TodayLocalPhase.READY ||
                        localHolder.state.value.phase == TodayLocalPhase.READING &&
                        localHolder.state.value.read?.data != null)
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
        }
    }

    val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
    val occurrenceDate = OccurrenceDate.from(clock)
    val active = plans.firstOrNull { it.id == runningId && PlanTime.isActive(it, nowMinutes, occurrenceDate) }
        ?: plans.firstOrNull { PlanTime.isActive(it, nowMinutes, occurrenceDate) }
    val todayKey = remember(clock) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.timeInMillis)) }
    val selectedKey = selectedDate ?: todayKey
    val selectedNowMinutes = if (selectedKey == todayKey) nowMinutes else 0
    val timeline = remember(plans, schedules, selectedKey, selectedNowMinutes) { todayTimeline(selectedKey, plans, schedules, selectedNowMinutes) }
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
                    } finally {
                        savingSchedule = false
                    }
                }
            }
        },
        onDelete = editingSchedule?.let { old -> {
            scope.launch {
                deleteSchedule(store, reminderScheduler, syncClient, old)
                showScheduleEditor = false
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
        floatingActionButton = { if (!loading) InkFab({ showCreateActions = true }, "新建安排") },
    ) { padding ->
        SyncRefreshHost(syncState.running, readError ?: syncState.error, { localHolder.refresh(currentLocalKey); syncClient.coordinator.refresh() },
            Modifier.fillMaxSize().padding(padding),
            loginRequired = syncState.loginRequired,
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
                                    onCompleted = { warning -> actionMessage = warning },
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
                        onAsk = onAsk,
                    ),
                )
            }
        }
    }
}

/**
 * 今日页的入场时钟键（v4.6）：首帧（开屏下）与完整今日页共用，日晷「转圈」与各块落笔只播一次、换树不重播不闪。
 */
private const val TODAY_INTRO = "today"

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
                todayTimeline(todayKey, data.plans, data.schedules, nowMinutes)
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
    val onAsk: () -> Unit = {},
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
    val isToday = model.selectedKey == model.todayKey
    val entries = model.timeline
    val typeOf: (TimelineEntry) -> String? = { e ->
        if (e.kind == TimelineKind.PLAN) model.plans.firstOrNull { it.id == e.id }?.type else null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 96.dp),
    ) {
        item {
            TodayHeader(model.clock, syncStatus, actions.onCreate)
        }
        item {
            AssistantBrief(entries, isToday, model.weather, actions.onAsk, actions.onWeatherInfo)
        }
        item {
            // 日晷：今日页的主角。弧段按类别着色，进行中为朱色加粗。
            // 表盘是一块整体图形：直径随可用宽度收缩（上限 280dp），盘内文字随盘缩放、不随系统字号放大，
            // 避免窄屏 / 大字体下中心时间撞上刻度数字。
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().padding(top = 10.dp).inkReveal(1, introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS), contentAlignment = Alignment.Center,
            ) {
                val dial = minOf(280.dp, maxWidth)
                val k = dial / 280.dp
                val px = LocalDensity.current
                fun dialSp(v: Float) = with(px) { (v.dp * k).toSp() }
                // BUG-102：全天日程不画 24 小时弧段，只在内圈画一道细环
                val layers = todayDialLayers(entries)
                SundialDial(
                    introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS,
                    arcs = layers.arcs.map { entry ->
                        DialArc(entry.startMin.coerceAtLeast(0), entry.endMin.coerceAtMost(1440), entryHue(entry, typeOf(entry)).color(),
                            emphasized = entry.isRunningNow)
                    },
                    allDayRing = layers.allDayRing?.let { entryHue(it, typeOf(it)).color() },
                    nowMinute = if (isToday) model.nowMinutes else model.selectedNowMinutes,
                    modifier = Modifier.size(dial),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isToday) {
                            // 十二时辰：给数字时间配一个有温度的名字
                            Text(com.planlock.design.shichenLabel(model.nowMinutes),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dialSp(12f), lineHeight = dialSp(16f), letterSpacing = dialSp(3f)),
                                color = InkTone.vermilion)
                            Text("%02d:%02d".format(Locale.US, model.nowMinutes / 60, model.nowMinutes % 60),
                                style = V2Tokens.numeralLarge.themed().copy(fontSize = dialSp(46f), lineHeight = dialSp(50f)), color = InkTone.text)
                        } else {
                            Text(model.selectedKey.takeLast(5).replace('-', '.'),
                                style = V2Tokens.numeralLarge.themed().copy(fontSize = dialSp(38f), lineHeight = dialSp(44f)), color = InkTone.text)
                        }
                        // v4.3（审查 U7g）：没有安排时盘内直接说一句，而不是只留空盘
                        Text(if (entries.isNotEmpty()) "${entries.size} 项安排" else if (isToday) "今日无事" else "这天无事",
                            style = InkText.caption.copy(fontSize = dialSp(12f), lineHeight = dialSp(16f)), color = InkTone.soft)
                    }
                }
            }
        }
        item {
            WeekIndicator(model.todayKey, model.selectedKey, model.plans, model.schedules, model.nowMinutes, actions.onSelectDate)
        }
        item {
            Column {
                if (!isToday) {
                    // v4.3（审查 U7d）：图标 + 文字，不再用「←」字符当图标
                    TextButton(onClick = actions.onBackToday, modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Today, null, tint = InkTone.vermilion, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("回到今天", color = InkTone.vermilion, style = MaterialTheme.typography.labelLarge)
                    }
                }
                model.active?.let { plan -> NowStrip(plan, model.nowMinutes) { actions.onPause(plan) } }
            }
        }
        if (entries.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)) }
            itemsIndexed(entries, key = { _, entry -> "entry-${entry.kind}-${entry.id}-${entry.startMin}" }) { index, entry ->
                TimelineRow(entry, entryHue(entry, typeOf(entry)), todayRowKind(entry, typeOf(entry)), Modifier.inkReveal(3 + index, introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS)) {
                    if (entry.kind == TimelineKind.PLAN) actions.onOpenPlan(entry.id) else actions.onOpenSchedule(entry.id)
                }
            }
        }
        if (model.streak > 0 || model.violations > 0 || model.unlocks > 0) {
            item {
                Text(
                    listOfNotNull(
                        "连续 ${model.streak} 天".takeIf { model.streak > 0 },
                        "违规 ${model.violations}".takeIf { model.violations > 0 },
                        "解锁 ${model.unlocks}".takeIf { model.unlocks > 0 },
                    ).joinToString("　"),
                    style = InkText.caption, color = InkTone.soft,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                )
            }
        }
        model.actionMessage?.let { message ->
            item { Text(message, Modifier.padding(top = 12.dp), color = InkTone.jade, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun greeting(clock: Calendar): String = when (clock.get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

@Composable
private fun TodayHeader(clock: Calendar, syncStatus: @Composable () -> Unit, onCreate: () -> Unit) {
    Row(Modifier.fillMaxWidth().inkReveal(0, introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            com.planlock.design.FitText(formatCurrentDate(clock), InkText.caption, InkTone.soft)
            Text(greeting(clock), style = InkText.masthead, color = InkTone.text)
        }
        syncStatus()
    }
}

/**
 * 助理简报：今日页顶部的一句话，由助理说出「接下来是什么」。点按进入助理继续对话。
 * 这是 AI 融入日常的入口：现在由本地规则生成，接入 AI 后换成个性化的一句。
 */
@Composable
private fun AssistantBrief(
    entries: List<TimelineEntry>,
    isToday: Boolean,
    weather: WeatherSnapshot?,
    onAsk: () -> Unit,
    onWeatherInfo: () -> Unit,
) {
    // BUG-102：优先级见 todayBriefLine（全天日程永不作为「正在进行」）
    val line = todayBriefLine(entries, isToday)
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).inkReveal(1, introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS)
            .clip(RoundedCornerShape(18.dp)).background(InkTone.raised)
            .clickable(onClick = onAsk)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantMark(markSize = 28.dp)
        // 简报换句（如一项安排开始 / 结束）时淡出淡入，让变化可见；减弱动画时直接切换
        val reduced = com.planlock.design.LocalReducedMotion.current
        androidx.compose.animation.AnimatedContent(
            targetState = line,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            transitionSpec = {
                if (reduced) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                else androidx.compose.animation.fadeIn(tween(240, delayMillis = 80)) togetherWith androidx.compose.animation.fadeOut(tween(120))
            },
            label = "brief-line",
        ) { text ->
            Text(text ?: "和${AssistantIdentity.NAME}说说今天", style = MaterialTheme.typography.bodyMedium,
                color = if (text != null) InkTone.text else InkTone.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        weather?.let { snapshot ->
            Text("${weatherLabel(snapshot.weatherCode)} ${"%.0f".format(Locale.US, snapshot.temperatureC)}°",
                style = InkText.caption, color = InkTone.soft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onWeatherInfo).padding(4.dp))
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
                count = todayTimeline(key, plans, schedules, if (key == todayKey) nowMinutes else 0).size,
            )
        }
    }
    val accent = InkTone.vermilion
    // v4.3（审查 U7f）：七格等分整行，并向两侧各借 14dp 页边距——日期格的可点区域在 360dp 屏上 ≥ 48dp
    //（320dp 窄屏约 43dp，为七格同排的上限）；每格高度 ≥ 56dp。
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).inkReveal(1, introKey = TODAY_INTRO, introLeadMs = com.planlock.design.TODAY_INTRO_LEAD_MS).layout { measurable, constraints ->
            val extra = 14.dp.roundToPx()
            val width = constraints.maxWidth + extra * 2
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
        }) {
        days.forEach { day ->
            val selected = day.dateKey == selectedKey
            val isToday = day.dateKey == todayKey
            val bg by animateColorAsState(if (selected) InkTone.inverse else androidx.compose.ui.graphics.Color.Transparent,
                tween(240, easing = Ink.Easing), label = "week-day")
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(onClickLabel = "查看这一天") { onSelect(day.dateKey) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "周${day.label} ${day.dayOfMonth} 日" + (if (isToday) "，今天" else "") +
                            (if (day.count > 0) "，${day.count} 项安排" else "，无安排")
                        this.selected = day.dateKey == selectedKey
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(day.label, style = InkText.caption.copy(fontSize = 11.sp), color = if (isToday) accent else InkTone.soft)
                Box(Modifier.padding(top = 4.dp).size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(bg),
                    contentAlignment = Alignment.Center) {
                    Text("${day.dayOfMonth}", fontFamily = InkTone.display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = when { selected -> InkTone.onInverse; isToday -> accent; else -> InkTone.text })
                }
                Box(Modifier.padding(top = 4.dp).size(4.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (day.count > 0) InkTone.faint else androidx.compose.ui.graphics.Color.Transparent))
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
private fun timelineColor(entry: TimelineEntry): androidx.compose.ui.graphics.Color = when (entry.state) {
    TimelineState.RUNNING -> InkTone.vermilion
    TimelineState.UPCOMING -> if (entry.kind == TimelineKind.PLAN) InkTone.text.copy(alpha = .75f) else InkTone.brass
    TimelineState.ENDED -> InkTone.faint.copy(alpha = .45f)
    TimelineState.PAUSED -> Ink.stone.copy(alpha = .6f)
}

@Composable
private fun WeatherInfoDialog(snapshot: WeatherSnapshot, fromCache: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("天气数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snapshot.location.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${"%.1f".format(Locale.US, snapshot.temperatureC)}°C · ${weatherLabel(snapshot.weatherCode)}")
                Text("观测时间 · ${snapshot.observedAt.takeLast(5)}")
                if (fromCache) Text("当前显示上次成功获取的数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("© Open-Meteo.com", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("CC BY 4.0", style = V2Tokens.labelMicro.themed(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/**
 * 此刻：一行名称、衬线倒计时、一根朱色细线；「结束」是文字动作。
 * v4.6（真机宽度 360dp）：「结束」移到卡片右上角——原来在右下角，正好落在右下「+」悬浮按钮下面被挡住一半；
 * 起止时间移到名称同一行右端，右下角只留倒计时与进度线，悬浮按钮压上去也不挡操作。
 */
@Composable
private fun NowStrip(plan: PlanSnapshotEntity, nowMinutes: Int, onPause: () -> Unit) {
    val remaining = PlanTime.remainingMinutes(plan, nowMinutes)
    val progress = PlanTime.progress(plan, nowMinutes).coerceIn(0f, 1f)
    val reduced = LocalReducedMotion.current
    val grow by androidx.compose.animation.core.animateFloatAsState(progress,
        if (reduced) androidx.compose.animation.core.snap() else tween(800, easing = Ink.Easing), label = "now-progress")
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(InkTone.vermilion.inkTint())
        .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkPulseDot(InkTone.vermilion, dotSize = 6.dp)
            Text(" 正在进行", style = InkText.caption.copy(fontWeight = FontWeight.SemiBold), color = InkTone.vermilion)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "提前结束计划" }) {
                Text("结束", color = InkTone.vermilion, style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(plan.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = InkTone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${PlanTime.format(plan.startTime)} — ${PlanTime.format(plan.endTime)}", style = InkText.meta, color = InkTone.soft,
                modifier = Modifier.padding(start = 8.dp), maxLines = 1)
        }
        Text("%02d:%02d".format(remaining / 60, remaining % 60), style = V2Tokens.numeralHero.themed().copy(fontSize = 48.sp, lineHeight = 54.sp), color = InkTone.text)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp).height(2.dp).background(InkTone.hairline)) {
            Box(Modifier.fillMaxWidth(grow).height(2.dp).background(InkTone.vermilion))
        }
    }
}

/** 一项安排的颜色：进行中（仅定时安排）= 朱；日程 = 琥珀；计划 = 按类型。 */
private fun entryHue(entry: TimelineEntry, type: String?): InkHue.Hue = when {
    entry.isRunningNow -> InkHue.seal
    entry.kind == TimelineKind.SCHEDULE -> InkHue.amber
    else -> InkHue.forType(type)
}

/** 时间卡：类别色淡底 + 左侧色条；已结束的一项退成淡纸色。 */
@Composable
private fun TimelineRow(entry: TimelineEntry, hue: InkHue.Hue, kind: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // v4.3（审查 U7a/b 方案 B）：无左色条；进行中 = 时刻前呼吸点；状态在最右；元信息「类型 粗 + 来源 软」
    val running = entry.isRunningNow
    val ended = entry.state == TimelineState.ENDED || entry.state == TimelineState.PAUSED
    val color = hue.color()
    val press = com.planlock.design.rememberInkPress()
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).then(Modifier.inkPressScale(press)).clip(RoundedCornerShape(16.dp))
            .background(if (ended) InkTone.sunk.copy(alpha = .6f) else hue.tint())
            .then(Modifier.inkPressClick(press, onClick = onClick))
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 13.dp, bottom = 13.dp).widthIn(min = 42.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) { InkPulseDot(color, dotSize = 7.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (entry.isAllDay) "全天" else "%02d:%02d".format(Locale.US, (entry.startMin / 60) % 24, entry.startMin % 60),
                    style = InkText.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = if (ended) InkTone.soft else color)
            }
            if (!entry.isAllDay) Text("%02d:%02d".format(Locale.US, (entry.endMin / 60) % 24, entry.endMin % 60),
                style = InkText.caption.copy(fontSize = 11.sp), color = InkTone.soft)
        }
        Column(Modifier.weight(1f).padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = if (ended) InkTone.soft else InkTone.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(kind, style = InkText.caption.copy(fontWeight = FontWeight.SemiBold), color = if (ended) InkTone.soft else color, maxLines = 1)
                if (entry.kind == TimelineKind.PLAN && kind != "计划") Text("计划", style = InkText.caption, color = InkTone.soft, maxLines = 1)
            }
        }
        val state = todayRowStateLabel(entry)
        if (state != null) Text(state, modifier = Modifier.padding(start = 6.dp, end = 14.dp),
            style = InkText.caption.copy(fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal),
            color = if (running) color else InkTone.soft, maxLines = 1)
        else Spacer(Modifier.width(14.dp))
    }
}

internal fun compactTimelineBottomSpacing(index: Int, itemCount: Int): Int =
    if (index >= 0 && index < itemCount - 1) 8 else 0

internal fun PlanSnapshotEntity.toCardData(state: PlanVisualState? = null) = PlanCardData(
    id = id,
    name = name,
    type = type,
    timeText = "${PlanTime.format(startTime)} 至 ${PlanTime.format(endTime)}",
    repeatText = repeatLabel(repeatRule),
    state = state ?: visualState(Calendar.getInstance()),
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
