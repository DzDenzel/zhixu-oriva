package com.planlock.feature.plan

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.unit.sp
import com.planlock.design.InkMasthead
import com.planlock.design.InkRoundAction
import com.planlock.design.InkFab
import com.planlock.design.InkHue
import com.planlock.design.color
import com.planlock.design.tint
import com.planlock.design.inkTint
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.inkReveal
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
import com.planlock.app.api.ConnectionStore
import com.planlock.app.api.TokenStore
import com.planlock.app.api.planLockPreferences
import com.planlock.app.engine.executionHaptic
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.RecordStore
import com.planlock.app.record.SyncConfirmation
import com.planlock.app.record.BulkDeletion
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
    val authState by TokenStore.state.collectAsState()
    val connectionState by ConnectionStore.state.collectAsState()
    val storedConfirmations by remember(store) { store.observeConfirmations() }.collectAsState(initial = emptyList())
    val storedBulkDeletion by remember(store) { store.observeBulkDeletion() }.collectAsState(initial = null)
    val canShowConfirmations = authState.localDataUserId != null && !authState.ownershipMismatch && !authState.signedOut
    val confirmations = if (canShowConfirmations) storedConfirmations else emptyList()
    val bulkDeletion = storedBulkDeletion.takeIf { canShowConfirmations }
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
    var selectedConfirmation by remember { mutableStateOf<SyncConfirmation?>(null) }
    var selectedBulkDeletion by remember { mutableStateOf<BulkDeletion?>(null) }
    var confirmationBusy by remember { mutableStateOf(false) }
    var confirmationError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scheduleListState = rememberLazyListState()

    PlanActionDialogHost(actionGate, pendingAction) { pendingAction = null }

    fun openConfirmation(value: SyncConfirmation) {
        confirmationError = null
        selectedBulkDeletion = null
        selectedConfirmation = value
    }

    fun openPlan(value: PlanSnapshotEntity) {
        val confirmation = confirmations.firstOrNull { it.affectedPlanId() == value.id }
        if (confirmation != null) openConfirmation(confirmation) else onOpenDetail(value.id)
    }

    fun cardData(value: PlanSnapshotEntity, state: PlanVisualState) = value.toCardData(state).copy(
        supportingText = if (confirmations.any { it.affectedPlanId() == value.id }) "需要确认" else null,
    )

    // A resolved record or changed account must not leave a stale version visible in a dialog.
    LaunchedEffect(confirmations, bulkDeletion) {
        selectedConfirmation?.let { selected ->
            val current = confirmations.firstOrNull { it.key == selected.key }
            if (current != selected) {
                selectedConfirmation = null
                if (current != null) confirmationError = "对方又修改了这条记录，请重新打开确认。"
            }
        }
        if (selectedBulkDeletion?.let { it != bulkDeletion || it.approved } == true) {
            selectedBulkDeletion = null
            if (bulkDeletion?.approved == false) confirmationError = "服务端记录已变化，请重新核对清理范围。"
        }
    }
    LaunchedEffect(authState.session.userId, authState.instanceId, authState.signedOut, connectionState.revision) {
        selectedConfirmation = null
        selectedBulkDeletion = null
        confirmationError = null
    }

    fun checkConfirmationSession() {
        val currentAuth = TokenStore.state.value
        check(currentAuth.session.userId == authState.session.userId &&
            ConnectionStore.state.value == connectionState && !currentAuth.ownershipMismatch && !currentAuth.signedOut) {
            "账号或连接已改变"
        }
    }

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
    val localRevision by store.localRevision.collectAsState()
    LaunchedEffect(syncState.revision, localRevision) { refresh() }

    selectedConfirmation?.let { selected ->
        SyncConfirmationDialog(selected, confirmationBusy, confirmationError,
            onDismiss = { selectedConfirmation = null; confirmationError = null },
            onResolve = { keepMine ->
                if (!confirmationBusy) {
                    confirmationBusy = true
                    confirmationError = null
                    scope.launch {
                        try {
                            checkConfirmationSession()
                            syncClient.resolveConfirmation(selected, keepMine)
                            selectedConfirmation = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            confirmationError = "处理未完成，请刷新后重试。"
                        } finally {
                            confirmationBusy = false
                        }
                    }
                }
            })
    }
    selectedBulkDeletion?.let { selected ->
        BulkDeletionDialog(selected, confirmationBusy, confirmationError,
            onDismiss = { selectedBulkDeletion = null; confirmationError = null },
            onApprove = {
                if (!confirmationBusy) {
                    confirmationBusy = true
                    confirmationError = null
                    scope.launch {
                        try {
                            checkConfirmationSession()
                            syncClient.approveBulkDeletion(selected)
                            selectedBulkDeletion = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            confirmationError = "清理未完成，请刷新后核对记录并重试。"
                        } finally {
                            confirmationBusy = false
                        }
                    }
                }
            })
    }

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
            }
        } },
    )

    val createAction = {
        if (shelfMode == PlanShelfMode.PLANS) onCreate(plans.isEmpty())
        else { editingSchedule = null; showScheduleEditor = true }
    }
    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.PLANS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = { InkFab(createAction, if (shelfMode == PlanShelfMode.PLANS) "新建计划" else "新建日程") },
    ) { padding ->
        SyncRefreshHost(syncState.running, syncState.error, { syncClient.coordinator.refresh() }, Modifier.padding(padding),
            loginRequired = syncState.loginRequired,
            errorEventKey = "${com.planlock.app.api.TokenStore.userId}:${syncState.attempts}") { syncStatus ->
        Column(Modifier.fillMaxSize()) {
            PlanShelfHeader(shelfMode, planCount = plans.size, scheduleCount = schedules.size, onChange = { shelfMode = it }, onCreate = createAction, syncStatus = syncStatus)
            SyncConfirmationStatus(confirmations, bulkDeletion, onSelect = ::openConfirmation,
                onBulkSelect = { confirmationError = null; selectedConfirmation = null; selectedBulkDeletion = it })
            if (confirmationError != null && selectedConfirmation == null && selectedBulkDeletion == null) {
                Text(confirmationError.orEmpty(), Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            when {
            loading && plans.isEmpty() && schedules.isEmpty() -> UIStatePanel(UiState.LOADING, modifier = Modifier.weight(1f))
            error != null && plans.isEmpty() && schedules.isEmpty() -> UIStatePanel(UiState.ERROR, error, { scope.launch { refresh() } }, Modifier.weight(1f).verticalScroll(rememberScrollState()))
            shelfMode == PlanShelfMode.SCHEDULES -> androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                ScheduleList(
                    occurrences = scheduleOccurrences,
                    deliveryMode = reminderScheduler.deliveryMode().name,
                    state = scheduleListState,
                    needsConfirmation = { schedule -> confirmations.any { it.kind == "schedule" && it.id == schedule.id } },
                    onOpen = { schedule ->
                        val confirmation = confirmations.firstOrNull { it.kind == "schedule" && it.id == schedule.id }
                        if (confirmation != null) openConfirmation(confirmation)
                        else { editingSchedule = schedule; showScheduleEditor = true }
                    },
                )
            }
            // 没有计划：页面就是空的，只留页头的「+」。空白区仍是可滚动容器，
            // 下拉手势才能经嵌套滚动传给 SyncRefreshHost（BUG-098）。
            plans.isEmpty() -> androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).semantics { contentDescription = "计划列表" },
            )
            else -> androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                // 默认只看「正在 / 接下来」；两者都没有时直接展示全部。
                val showAll = allExpanded || (overview.current.isEmpty() && overview.upcoming.isEmpty())
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    if (!showAll) {
                        if (overview.current.isNotEmpty()) {
                            item { ShelfLabel("正在进行", dot = InkTone.vermilion) }
                            items(overview.current, key = { "current-${it.id}" }) { plan ->
                                PlanCard(
                                    cardData(plan, PlanVisualState.EXECUTING),
                                    onClick = { openPlan(plan) },
                                    modifier = Modifier.animateItem(tween(if (reducedMotion) 0 else 180), tween(if (reducedMotion) 0 else 220), tween(if (reducedMotion) 0 else 180)),
                                    sharedKey = "plan-${plan.id}",
                                    trailingAction = {
                                        TextButton(enabled = confirmations.none { it.affectedPlanId() == plan.id }, onClick = {
                                            scope.launch {
                                                requestOrRunPlanAction(actionGate, plan, ProtectedPlanAction.PAUSE,
                                                    onPrompt = { pendingAction = it },
                                                    onCompleted = { executionHaptic(context) })
                                            }
                                        }) { Text("结束", color = InkTone.vermilion) }
                                    },
                                )
                            }
                        }
                        if (overview.upcoming.isNotEmpty()) {
                            item { ShelfLabel("接下来", dot = InkHue.study.color()) }
                            overview.upcoming.groupBy { it.dayOffset }.forEach { (offset, group) ->
                                item(key = "day-$offset") {
                                    Text(nextLabel(offset), style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint,
                                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp))
                                }
                                itemsIndexed(group, key = { _, o -> "upcoming-${o.plan.id}" }) { index, occurrence ->
                                    PlanCard(cardData(occurrence.plan, PlanVisualState.UPCOMING),
                                        onClick = { openPlan(occurrence.plan) }, modifier = Modifier.inkReveal(index))
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { allExpanded = true }, modifier = Modifier.padding(top = 8.dp)
                                .semantics { contentDescription = "全部计划，已收起" }) {
                                Text("全部 ${plans.distinctBy { it.id }.size} 项 →", color = InkTone.soft, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        // 只有暂停 / 时间不完整的计划时，直接把它们列出来，不再显示空的「全部」
                        val onlyStored = overview.scheduled.isEmpty()
                        if (!onlyStored) item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ShelfLabel("全部", Modifier.weight(1f))
                                if (overview.current.isNotEmpty() || overview.upcoming.isNotEmpty()) {
                                    TextButton(onClick = { allExpanded = false; selectedCategory = null },
                                        modifier = Modifier.semantics { contentDescription = "全部计划，已展开" }) {
                                        Text("收起", color = InkTone.soft)
                                    }
                                }
                            }
                        }
                        if (overview.categoryCounts.size >= 2) item {
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                item { CategoryFilter("全部", selectedCategory == null) { selectedCategory = null } }
                                items(overview.categoryCounts.keys.toList()) { category ->
                                    CategoryFilter(category, selectedCategory == category) { selectedCategory = category }
                                }
                            }
                        }
                        itemsIndexed(filteredAll, key = { _, plan -> "all-${plan.id}" }) { index, plan ->
                            PlanCard(cardData(plan, if (plan in overview.current) PlanVisualState.EXECUTING else PlanVisualState.UPCOMING),
                                onClick = { openPlan(plan) }, modifier = Modifier.inkReveal(index))
                        }
                        if (onlyStored) {
                            item { ShelfLabel("已暂停或时间不完整", dot = com.planlock.design.Ink.stone) }
                            items(overview.stored, key = { "stored-${it.id}" }) { plan ->
                                PlanCard(cardData(plan, PlanVisualState.PAUSED), onClick = { openPlan(plan) })
                            }
                        } else if (overview.stored.isNotEmpty()) {
                            item {
                                TextButton(onClick = { storageExpanded = !storageExpanded }, modifier = Modifier.padding(top = 8.dp)
                                    .semantics { contentDescription = if (storageExpanded) "暂停计划，已展开" else "暂停计划，已收起" }) {
                                    Text("已暂停或时间不完整 ${overview.stored.size} 项 ${if (storageExpanded) "↑" else "↓"}", color = InkTone.soft,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (storageExpanded) items(overview.stored, key = { "stored-${it.id}" }) { plan ->
                                PlanCard(cardData(plan, PlanVisualState.PAUSED), onClick = { openPlan(plan) })
                            }
                        }
                    }
                }
            }
            }
        }
    }
    }
}

/**
 * 页头（v4.1）：「计划 / 日程」两段式切换。轨道是下沉的纸槽，选中段是抬起的纸片；
 * 每段一枚小印——计划用朱（行），日程用琥珀（记）——选中时实色、未选中时淡底，
 * 再带一个数量，两种内容一眼可分，不再只是两个同色大字。
 */
@Composable
private fun PlanShelfHeader(
    mode: PlanShelfMode,
    planCount: Int,
    scheduleCount: Int,
    onChange: (PlanShelfMode) -> Unit,
    @Suppress("UNUSED_PARAMETER") onCreate: () -> Unit,
    syncStatus: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShelfSwitch(mode, planCount, scheduleCount, onChange, Modifier.weight(1f))
        syncStatus()
    }
}

@Composable
private fun ShelfSwitch(
    mode: PlanShelfMode,
    planCount: Int,
    scheduleCount: Int,
    onChange: (PlanShelfMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.widthIn(max = 360.dp).height(54.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(17.dp))
            .background(InkTone.sunk)
            .border(1.dp, InkTone.hairline.copy(alpha = .7f), androidx.compose.foundation.shape.RoundedCornerShape(17.dp))
            .padding(4.dp)
            .selectableGroup(),
    ) {
        val half = maxWidth / 2
        val offset by androidx.compose.animation.core.animateDpAsState(
            if (mode == PlanShelfMode.PLANS) 0.dp else half,
            if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 520f),
            label = "shelf-indicator",
        )
        // 抬起的纸片：跟随选中段滑动
        androidx.compose.material3.Surface(
            modifier = Modifier.offset(x = offset).width(half).fillMaxHeight(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
            color = InkTone.raised,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, InkTone.hairline.copy(alpha = .5f)),
        ) {}
        Row(Modifier.fillMaxSize()) {
            ShelfSegment("行", "计划", planCount, InkHue.seal, mode == PlanShelfMode.PLANS, Modifier.weight(1f)) { onChange(PlanShelfMode.PLANS) }
            ShelfSegment("记", "日程", scheduleCount, InkHue.amber, mode == PlanShelfMode.SCHEDULES, Modifier.weight(1f)) { onChange(PlanShelfMode.SCHEDULES) }
        }
    }
}

@Composable
private fun ShelfSegment(
    glyph: String,
    label: String,
    count: Int,
    hue: InkHue.Hue,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tone = hue.color()
    val text by androidx.compose.animation.animateColorAsState(if (selected) InkTone.text else InkTone.soft, label = "shelf-text")
    val sealFill by androidx.compose.animation.animateColorAsState(if (selected) tone else hue.tint(), label = "shelf-seal")
    val sealInk by androidx.compose.animation.animateColorAsState(if (selected) InkTone.onAccent else tone, label = "shelf-seal-ink")
    Row(
        modifier.fillMaxHeight()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(26.dp).background(sealFill, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(glyph, color = sealInk, fontFamily = InkTone.display, fontWeight = FontWeight.Black, fontSize = 14.sp,
                style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        // 页头右侧胶囊展开时切换条会变窄：字号自动收小，不裁字
        com.planlock.design.FitText(label,
            androidx.compose.ui.text.TextStyle(fontFamily = InkTone.display, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 17.sp),
            text, Modifier.weight(1f, fill = false), minSize = 12.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(if (count > 99) "99+" else count.toString(), color = if (selected) tone else InkTone.faint,
            style = InkText.meta.copy(fontSize = 11.sp), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ShelfLabel(text: String, modifier: Modifier = Modifier, dot: androidx.compose.ui.graphics.Color? = null) {
    Row(modifier.padding(start = 10.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        dot?.let { Box(Modifier.padding(end = 8.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(it)) }
        Text(text, style = MaterialTheme.typography.titleMedium, color = InkTone.text)
    }
}

@Composable
private fun ScheduleList(
    occurrences: List<ScheduleOccurrence>,
    deliveryMode: String,
    state: androidx.compose.foundation.lazy.LazyListState,
    needsConfirmation: (ScheduleSnapshotEntity) -> Boolean,
    onOpen: (ScheduleSnapshotEntity) -> Unit,
) {
    // 没有日程：留白，但保留可滚动容器以接住下拉刷新手势（BUG-098）。
    if (occurrences.isEmpty()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics { contentDescription = "日程列表" },
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = state,
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        occurrences.groupBy { it.date.substring(0, 7) }.forEach { (month, group) ->
            item(key = "month-$month") {
                Text(month.substring(5).trimStart('0') + " 月", style = MaterialTheme.typography.titleMedium, color = InkTone.text,
                    modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 4.dp))
            }
            itemsIndexed(group, key = { _, o -> "${o.date}-${o.schedule.id}" }) { index, occurrence ->
                Row(
                    Modifier.fillMaxWidth().inkReveal(index).padding(vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(InkHue.amber.tint())
                        .clickable { onOpen(occurrence.schedule) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.widthIn(min = 52.dp).padding(end = 6.dp)) {
                        Text(occurrence.date.substring(8), style = InkText.numeral.copy(fontSize = 22.sp, lineHeight = 26.sp), color = InkHue.amber.color())
                        Text(weekdayOf(occurrence.date), style = InkText.meta.copy(fontSize = 10.sp), color = InkTone.faint)
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(occurrence.schedule.title, style = MaterialTheme.typography.titleMedium, color = InkTone.text, maxLines = 1)
                        Text(listOfNotNull(
                            if (occurrence.schedule.allDay) "全天" else occurrence.schedule.startTime?.take(5),
                            occurrence.schedule.location?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "), style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint, maxLines = 1)
                        if (needsConfirmation(occurrence.schedule)) Text("需要确认", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.vermilion)
                    }

                }
            }
        }
        if (deliveryMode != "EXACT") item {
            Text("提醒可能有轻微延迟（系统未授予精确闹钟）", style = InkText.meta.copy(fontSize = 10.sp), color = InkTone.faint,
                modifier = Modifier.padding(start = 10.dp, top = 16.dp))
        }
    }
}

private fun weekdayOf(isoDate: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)!!
    val day = Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_WEEK)
    "周" + listOf("日", "一", "二", "三", "四", "五", "六")[day - 1]
}.getOrDefault("")

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
private fun CategoryFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    val hue = if (label == "全部") InkTone.text else InkHue.forType(label).color()
    val bg by androidx.compose.animation.animateColorAsState(if (selected) hue else hue.inkTint(), label = "category")
    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        color = if (selected) com.planlock.design.InkTone.onAccent else hue,
        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp))
}

private fun nextLabel(offset: Int): String = when (offset) {
    0 -> "今天"
    1 -> "明天"
    else -> "$offset 天后"
}
