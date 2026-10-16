package com.planlock.feature.stats

import com.planlock.design.ChoiceChips
import com.planlock.design.SundialDial
import com.planlock.design.DialArc
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.TimelineState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.animation.togetherWith
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.DailyStat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import com.planlock.design.LocalReducedMotion
import com.planlock.design.V2Tokens
import com.planlock.design.InkHue
import com.planlock.design.color
import com.planlock.design.tint
import com.planlock.design.inkTint
import com.planlock.design.Ink
import com.planlock.design.InkMasthead
import com.planlock.design.InkRing
import com.planlock.design.InkSegmented
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.themed
import com.planlock.design.LocalDarkTheme
import com.planlock.design.inkReveal
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import com.planlock.design.SectionHeading
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.IntrinsicSize
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.planlock.app.sync.AppSync
import com.planlock.design.SyncRefreshHost
import com.planlock.design.PersistentStatus
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.planlock.app.security.PermissionChecker
import com.planlock.app.security.PermissionKind
import com.planlock.app.security.ObservationAvailability
import com.planlock.app.security.observationAvailability
import com.planlock.design.rememberPermissionPresentation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.ApiClient
import com.planlock.app.api.dto.ApiResponse
import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.ViolationAppStat
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.record.RecordStore
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.BehaviorAggregateEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.design.StatTile
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.planLockAtmosphere
import com.planlock.design.PageAtmosphere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ExportState { IDLE, EXPORTING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val sync = remember { AppSync.get(context) }
    val syncState by sync.coordinator.state.collectAsState()
    val api = remember { ApiClient.create() }
    val store = remember { RecordStore(context) }
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<Map<String, StatsResponse>>(emptyMap()) }
    var selectedPeriod by remember { mutableStateOf("week") }
    var today by remember { mutableStateOf<List<ExecutionRecordDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var planNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var exportState by remember { mutableStateOf(ExportState.IDLE) }
    val snackbar = remember { SnackbarHostState() }
    var behavior by remember { mutableStateOf<List<BehaviorAggregateEntity>>(emptyList()) }
    var cachedAt by remember { mutableStateOf<Long?>(null) }
    var legacyExpanded by remember { mutableStateOf(false) }
    val observation = rememberPermissionPresentation().observation

    suspend fun load() {
        try {
            val localPlans = store.plans()
            plans = localPlans
            behavior = store.behaviorAggregates()
            planNames = localPlans.associate { it.id to it.name }
            val snapshot = sync.cache.read(com.planlock.app.api.TokenStore.userId)
            reports = listOf("day", "week", "month").mapNotNull { period ->
                snapshot.stats(period)?.let { period to it }
            }.toMap()
            cachedAt = snapshot.updatedAtEpochMs
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
            today = snapshot.today?.takeIf { it.date == date }?.executions.orEmpty()
            offline = syncState.error != null
            readError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = "本机统计读取失败，请重试"
        }
        loading = false
    }

    suspend fun export(uri: Uri, format: String) {
        exportState = ExportState.EXPORTING
        try {
            val body = withContext(Dispatchers.IO) {
                if (format == "ics") api.exportPlans().string() else api.exportRecords(format).string()
            }
            withContext(Dispatchers.IO) {
                val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法打开导出文件" }
                output.bufferedWriter(Charsets.UTF_8).use {
                    // Windows CSV readers need the UTF-8 signature to preserve Chinese text.
                    if (format == "csv") it.write("\uFEFF")
                    it.write(body)
                }
            }
            exportState = ExportState.IDLE
            snackbar.showSnackbar("导出完成")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            exportState = ExportState.IDLE
            snackbar.showSnackbar("导出失败，请检查网络后重试")
        }
    }

    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> uri?.let { scope.launch { export(it, "ics") } } }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { scope.launch { export(it, "csv") } } }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { scope.launch { export(it, "json") } } }

    val localRevision by store.localRevision.collectAsState()
    LaunchedEffect(syncState.revision, localRevision) { load() }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.STATS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { com.planlock.design.InkSnackbarHost(snackbar) },
    ) { padding ->
        SyncRefreshHost(syncState.running, syncState.error, { sync.coordinator.refresh() },
            loginRequired = syncState.loginRequired,
            errorEventKey = "${com.planlock.app.api.TokenStore.userId}:${syncState.attempts}") { syncStatus ->
        when {
            loading -> {
                UIStatePanel(UiState.LOADING, "正在汇总本周数据", modifier = Modifier.padding(padding))
                androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 26.dp)) { syncStatus() }
            }
            // 没有任何数据：只留页头。读取失败时才说明。
            reports.isEmpty() && behavior.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp)) {
                item { StatsStatusHeader(syncStatus) }
                if (readError != null) item { UIStatePanel(UiState.ERROR, readError, onAction = { scope.launch { load() }; sync.coordinator.refresh() }) }
            }
            else -> {
                val value = reports[selectedPeriod]
                val presentation = reportPresentation(value, behavior, plans.mapTo(mutableSetOf()) { it.id })
                val ranking = presentation.ranking
                val violationDuration = presentation.durationMs
                val reportRows = value?.let {
                    planReportRows(plans, it.planExecution, scopeDates(selectedPeriod, OccurrenceDate.from(Calendar.getInstance())))
                }.orEmpty()
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        StatsStatusHeader(syncStatus)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            InkSegmented(listOf("day" to "今日", "week" to "本周", "month" to "本月"),
                                selectedPeriod, { selectedPeriod = it }, Modifier.weight(1f).inkReveal(1))
                        }
                        Text(
                            when {
                                offline || presentation.local -> localDataLabel(cachedAt)
                                cachedAt != null -> "更新于 ${formatClock(cachedAt!!)}"
                                else -> "尚未同步"
                            },
                            color = if (offline || presentation.local) InkTone.brass else InkTone.faint,
                            style = InkText.meta.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (observation != ObservationAvailability.PRECISE) item { ObservationNotice(observation) }
                    readError?.let { error -> item { PersistentStatus("统计读取异常 · 详情", error) } }
                    // 每个时间尺度一张自己的图：今日 = 日晷，本周 = 七日柱，本月 = 月历
                    item {
                        androidx.compose.animation.AnimatedContent(
                            targetState = selectedPeriod,
                            transitionSpec = {
                                val order = listOf("day", "week", "month")
                                val forward = order.indexOf(targetState) > order.indexOf(initialState)
                                (androidx.compose.animation.fadeIn(tween(280)) + androidx.compose.animation.slideInHorizontally(tween(320, easing = Ink.Easing)) { if (forward) it / 4 else -it / 4 })
                                    .togetherWith(androidx.compose.animation.fadeOut(tween(160)))
                            },
                            label = "period-chart",
                        ) { period -> PeriodChart(period, reports[period], plans) }
                    }
                    // 全站唯一的一块墨：这段时间被打断了多久
                    item { DurationHero(violationDuration, ranking.sumOf { it.count }, ranking.size, periodLabel(selectedPeriod), presentation.local, periodHue(selectedPeriod)) }
                    item {
                        val earlyCount = value?.planExecution?.sumOf { it.earlyEndedCount } ?: 0
                        ReportTiles(value?.continuousDays ?: 0, value?.completedCount ?: 0,
                            earlyCount, periodLabel(selectedPeriod))
                    }
                    if (ranking.isNotEmpty()) {
                        item { SectionTitle("分心来源", if (value == null) "本地累计" else scopeDateRangeLabel(selectedPeriod, OccurrenceDate.from(Calendar.getInstance()))) }
                        val maxDuration = ranking.maxOf { it.totalDurationMs }.coerceAtLeast(1L)
                        itemsIndexed(ranking, key = { _, stat -> stat.packageName }) { index, item -> ViolationRankRow(index + 1, item, maxDuration) }
                    }
                    if (reportRows.isNotEmpty()) {
                        item { SectionTitle("完成情况", "计划 vs 实际") }
                        items(reportRows, key = { it.planId }) { PlanReportRowView(it) }
                    }
                    if (!value?.earlyEndings.isNullOrEmpty()) {
                        item { SectionTitle("提前结束", "最近 ${value!!.earlyEndings.size} 条") }
                        items(value!!.earlyEndings, key = { "${it.planId}-${it.at}-${it.method}" }) { ending ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(planNames[ending.planId] ?: "计划 #${ending.planId}", style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                                Text("${ending.at ?: "时间未知"} · ${ending.method ?: "主动结束（未要求密码确认）"}", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
                            }
                        }
                    }
                    if (today.isNotEmpty()) {
                        item { SectionTitle("今日明细", "${today.size} 条") }
                        items(today, key = { "${it.planId}-${it.startedAt}-${it.status}" }) { record ->
                            ExecutionRow(record, planNames[record.planId])
                        }
                    }
                    if ((value?.legacyViolationCount ?: 0) > 0) item {
                        Column(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { legacyExpanded = !legacyExpanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text("旧版违规记录 ${if (legacyExpanded) "↑" else "↓"}", color = InkTone.soft)
                            }
                            if (legacyExpanded) Text("${value!!.legacyViolationCount} 次 · v1.0 为被拦下的未遂尝试，只读展示，不计入上方时长与排行。", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
                        }
                    }
                    item {
                        // 数据归你：三种格式一行排开
                        val busy = exportState == ExportState.EXPORTING
                        Column(Modifier.padding(top = 18.dp, bottom = 24.dp)) {
                            Text(if (busy) "导出中…" else "导出", style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                ExportChip("ICS", "日历", busy) { icsLauncher.launch("planlock-plans.ics") }
                                ExportChip("CSV", "表格", busy) { csvLauncher.launch("planlock-records.csv") }
                                ExportChip("JSON", "完整", busy) { jsonLauncher.launch("planlock-records.json") }
                            }
                        }
                    }
                }
            }
        }
    }
}

}

@Composable
private fun ObservationNotice(availability: ObservationAvailability) {
    PersistentStatus(
        label = when (availability) {
            ObservationAvailability.PRECISE -> "应用记录 · 精确 · 详情"
            ObservationAvailability.APPROXIMATE -> "应用记录 · 近似 · 详情"
            ObservationAvailability.UNAVAILABLE -> "应用记录 · 当前不可观测 · 详情"
        },
        detail = when (availability) {
            ObservationAvailability.PRECISE -> "服务正在运行，按精确来源记录。"
            ObservationAvailability.APPROXIMATE -> "来源：使用情况访问；可能遗漏短时切换。"
            ObservationAvailability.UNAVAILABLE -> "计划仍可执行；未取得数据来源，无法记录跨应用时段。"
        },
    )
}

@Composable
private fun DurationHero(durationMs: Long, count: Int, appCount: Int, scopeLabel: String, local: Boolean, hue: InkHue.Hue = InkHue.seal) {
    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val on = InkTone.onSlab
    // 墨块上的强调色取亮色版本，保证在深底上看得清
    val accent by androidx.compose.animation.animateColorAsState(hue.dark, tween(320), label = "hero-accent")
    Box(Modifier.fillMaxWidth().inkReveal(2).clip(shape).background(InkTone.slab).padding(22.dp)) {
        // 水印字只作底纹：放进 matchParentSize 的层里，不撑高墨块（否则底部留出一大块空白）
        Box(Modifier.matchParentSize()) {
            Text("衡", modifier = Modifier.align(Alignment.TopEnd).offset(x = 26.dp, y = (-50).dp).wrapContentSize(unbounded = true),
                fontFamily = InkTone.display, fontWeight = FontWeight.Black, fontSize = 160.sp, color = on.copy(alpha = .05f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (local) "DISTRACTION · 本地累计（时:分:秒）" else "DISTRACTION · 分心时长（时:分:秒）", style = InkText.eyebrow, color = accent)
            AdaptiveDuration(reportDurationLabel(durationMs))
            Text(
                if (count == 0) "这段时间没有分心记录" else "${if (local) "本地累计" else scopeLabel}分心 $count 次 · 来自 $appCount 个应用",
                color = on.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium,
            )
            if (local) Text("本地累计，未按当前时间范围筛选", color = on.copy(alpha = .6f), style = InkText.meta)
            ScaleRuler(count, on, accent)
        }
    }
}

/** 衡尺：24 格刻度，按分心次数点亮朱色刻度——一眼看出「这段时间被打断了多少次」。 */
@Composable
private fun ScaleRuler(count: Int, on: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color) {
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(count) { if (!reduced) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = Ink.Easing)) } }
    Canvas(Modifier.fillMaxWidth().height(26.dp).padding(top = 8.dp)) {
        val ticks = 24
        val lit = (count.coerceAtMost(ticks) * grow.value).toInt()
        val step = size.width / (ticks - 1)
        for (i in 0 until ticks) {
            val x = i * step
            val major = i % 6 == 0
            val h = if (major) size.height else size.height * .55f
            drawLine(if (i < lit) accent else on.copy(alpha = .25f),
                androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = if (i < lit) 3.dp.toPx() else 1.5.dp.toPx())
        }
    }
}

@Composable
private fun AdaptiveDuration(value: String) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val styles = listOf(V2Tokens.numeralHero.themed(), V2Tokens.numeralLarge.themed(), V2Tokens.numeralMedium.themed(),
            MaterialTheme.typography.bodyLarge)
        val style = styles.firstOrNull { measurer.measure(value, it, softWrap = false).size.width <= constraints.maxWidth }
            ?: styles.last()
        Text(value, style = style, color = InkTone.onSlab)
    }
}

@Composable
private fun ExportChip(tag: String, label: String, busy: Boolean, onClick: () -> Unit) {
    val hue = when (tag) { "ICS" -> InkHue.study; "CSV" -> InkHue.work; else -> InkHue.sleep }.color()
    Surface(onClick = onClick, enabled = !busy, shape = RoundedCornerShape(14.dp), color = hue.inkTint()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(tag, style = InkText.meta.copy(fontWeight = FontWeight.Bold), color = hue)
            Text(label, style = InkText.meta.copy(fontSize = 10.sp), color = InkTone.faint)
        }
    }
}

@Composable
private fun ExportRow(tag: String, title: String, detail: String, busy: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium, color = InkTone.raised,
        border = androidx.compose.foundation.BorderStroke(1.dp, InkTone.hairline),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(54.dp).background(InkTone.slab, RoundedCornerShape(8.dp)).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(tag, style = InkText.eyebrow.copy(letterSpacing = 1.sp), color = InkTone.onSlab)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(if (busy) "导出中…" else title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
            }
            androidx.compose.material3.Icon(Icons.Filled.Download, null, tint = InkTone.vermilion)
        }
    }
}

@Composable
private fun ReportTiles(streak: Int, completed: Int, early: Int, period: String) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp || fontScale > 1.15f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedStatTile(streak, " 天", "连续达成", period, hue = InkHue.sport.color(), modifier = Modifier.fillMaxWidth())
                AnimatedStatTile(completed, label = "完成", trend = period, hue = InkHue.study.color(), modifier = Modifier.fillMaxWidth())
                AnimatedStatTile(early, label = "提前结束", trend = period, danger = early > 0, hue = InkHue.amber.color(), modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedStatTile(streak, " 天", "连续达成", period, hue = InkHue.sport.color(), modifier = Modifier.weight(1f).fillMaxHeight())
                AnimatedStatTile(completed, label = "完成", trend = period, hue = InkHue.study.color(), modifier = Modifier.weight(1f).fillMaxHeight())
                AnimatedStatTile(early, label = "提前结束", trend = period, danger = early > 0, hue = InkHue.amber.color(), modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    SectionHeading(title, detail)
}

@Composable
private fun ViolationRankRow(rank: Int, item: ViolationAppStat, maxDuration: Long) {
    val share = (item.totalDurationMs.toFloat() / maxDuration).coerceIn(0f, 1f)
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) share else 0f) }
    LaunchedEffect(share) { if (!reduced) grow.animateTo(share, tween(700, delayMillis = rank * 60, easing = Ink.Easing)) else grow.snapTo(share) }
    val accent = listOf(InkHue.seal, InkHue.amber, InkHue.rose, InkHue.sleep, InkHue.study, InkHue.work)[(rank - 1).coerceIn(0, 5)].color()
    Row(Modifier.fillMaxWidth().inkReveal(rank).padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(accent.inkTint())
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(rank), style = InkText.numeral.copy(fontSize = 22.sp), color = accent,
            modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(item.appName?.takeIf { it.isNotBlank() } ?: item.packageName, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text(reportDurationLabel(item.totalDurationMs), style = InkText.meta.copy(fontWeight = FontWeight.Bold), color = accent)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent.copy(alpha = .18f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(grow.value).height(4.dp).background(accent, RoundedCornerShape(2.dp)))
            }
            Text("${item.count} 次${if (item.approxDurationMs > 0) " · 含近似" else " · 精确记录"}", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
        }
    }
}

@Composable
private fun PlanReportRowView(item: PlanReportRow) {
    val ratio = if (item.plannedDurationMs > 0) (item.actualDurationMs.toFloat() / item.plannedDurationMs).coerceIn(0f, 1f) else 0f
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = InkHue.sport.tint()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text("计划 ${reportDurationLabel(item.plannedDurationMs)}  /  实际 ${reportDurationLabel(item.actualDurationMs)}", style = InkText.meta, color = InkTone.soft)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("完成 ${item.completedCount}", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.jade)
                    Text("提前结束 ${item.earlyEndedCount}", style = InkText.meta.copy(fontSize = 11.sp), color = if (item.earlyEndedCount > 0) InkTone.brass else InkTone.faint)
                    Text("未执行 ${item.missedCount}", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
                }
            }
            InkRing(ratio, Modifier.size(54.dp), color = InkHue.sport.color(), track = InkHue.sport.color().copy(alpha = .18f), stroke = 4.dp) {
                Text("${(ratio * 100).toInt()}%", style = InkText.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = InkTone.text)
            }
        }
    }
}

private fun periodLabel(period: String) = when (period) {
    "day" -> "今日"
    "month" -> "本月"
    else -> "本周"
}

private fun formatClock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun BehaviorRow(item: BehaviorAggregateEntity, planName: String?) {
    val source = if (item.sourceLevel == "ACCESSIBILITY") "精确 · 无障碍" else "近似 · 使用情况访问估算"
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(item.packageName, style = MaterialTheme.typography.titleMedium)
                Text("${planName ?: "计划 #${item.planId}"} · $source · ${item.occurrenceCount} 次", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDuration(item.totalDurationMs), style = InkText.meta, color = InkTone.vermilion)
        }
    }
}

private fun formatDuration(millis: Long): String {
    return reportDurationLabel(millis)
}

// 两份接口数据均有效才提交在线快照，业务失败沿用页面的本地降级流程。
internal fun statsSnapshot(
    stats: ApiResponse<StatsResponse>,
    today: ApiResponse<TodaySummary>
): Pair<StatsResponse, List<ExecutionRecordDto>> {
    check(stats.code == 0 && today.code == 0) { "统计接口返回业务错误" }
    return checkNotNull(stats.data) to checkNotNull(today.data).executions.orEmpty()
}

@Composable
private fun InsightHero(streak: Int, completed: Int, daily: List<DailyStat>) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.primary) {
        Box(Modifier.background(InkTone.slab).padding(22.dp)) {
            Column {
                Text("本周", style = V2Tokens.labelMicro.themed(), color = InkTone.onSlab.copy(alpha = .72f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$streak", style = V2Tokens.numeralHero.themed(), color = InkTone.onSlab)
                    Text(" 天连续", color = InkTone.onSlab.copy(alpha = .82f), modifier = Modifier.padding(start = 8.dp, bottom = 9.dp))
                }
                Text("本周完成 $completed 次 · 每一次完成都会留下刻度", color = InkTone.onSlab.copy(alpha = .8f))
                WeekChart(daily, true)
            }
        }
    }
}

@Composable
private fun AnimatedStatTile(
    value: Int,
    suffix: String = "",
    label: String,
    trend: String,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    hue: androidx.compose.ui.graphics.Color? = null,
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val animatedValue by animateIntAsState(targetValue = if (started) value else 0, animationSpec = tween(450), label = "stat-number")
    StatTile("$animatedValue$suffix", label, trend, danger, modifier, hue)
}

@Composable
private fun WeekChart(daily: List<DailyStat>, onBrand: Boolean = false) {
    val growth = remember { Animatable(0f) }
    LaunchedEffect(daily) { growth.snapTo(0f); growth.animateTo(1f, tween(500)) }
    val primary = if (onBrand) InkTone.onSlab else MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = if (onBrand) androidx.compose.ui.graphics.Color.Transparent else surface.copy(alpha = .45f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(if (onBrand) 88.dp else 120.dp)) {
                val slot = size.width / daily.size.coerceAtLeast(1)
                val max = daily.maxOfOrNull { it.completed }?.coerceAtLeast(1) ?: 1
                daily.forEachIndexed { index, item ->
                    val height = (size.height * item.completed / max.toFloat() * growth.value).coerceAtLeast(4f)
                    drawRoundRect(primary, topLeft = androidx.compose.ui.geometry.Offset(index * slot + slot * .2f, size.height - height), size = androidx.compose.ui.geometry.Size(slot * .6f, height.coerceAtLeast(0f)), cornerRadius = CornerRadius(10f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                daily.forEach { Text(weekdayLabel(it.date), style = MaterialTheme.typography.labelMedium, color = if (onBrand) InkTone.onSlab.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun ExecutionRow(record: ExecutionRecordDto, planName: String?) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(planName ?: "计划 #${record.planId}", style = MaterialTheme.typography.titleMedium); Text(record.startedAt ?: "未记录开始时间", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(executionStatusLabel(record.status), style = InkText.eyebrow, color = InkTone.jade)
        }
    }
}

private fun isoDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

internal fun normalizedWeek(values: List<DailyStat>, today: String = isoDateFormat().format(Date())): List<DailyStat> {
    val formatter = isoDateFormat()
    val byDate = values.mapNotNull { item ->
        item.date?.takeIf { value -> runCatching { formatter.parse(value) }.getOrNull() != null }
            ?.let { it to item.completed }
    }.toMap()
    val todayDate = requireNotNull(formatter.parse(today)) { "Invalid date: $today" }
    return (6 downTo 0).map { offset ->
        val date = Calendar.getInstance().apply {
            time = todayDate
            add(Calendar.DAY_OF_MONTH, -offset)
        }.let { formatter.format(it.time) }
        DailyStat(date, byDate[date] ?: 0)
    }
}

private fun weekdayLabel(value: String?): String {
    val date = value?.let { runCatching { isoDateFormat().parse(it) }.getOrNull() } ?: return "—"
    val day = Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_WEEK)
    return listOf("日", "一", "二", "三", "四", "五", "六")[day - 1]
}


// Keep the same title/status geometry for cached reports and empty/error states.
@Composable
private fun StatsStatusHeader(syncStatus: @Composable () -> Unit) {
    InkMasthead(eyebrow = "", title = "统计", modifier = Modifier.padding(top = 20.dp).inkReveal(0), trailing = { syncStatus() })
}

/** 每个时间尺度的主色：今日 = 朱，本周 = 靛，本月 = 黛。 */
private fun periodHue(period: String): InkHue.Hue = when (period) {
    "day" -> InkHue.seal
    "month" -> InkHue.work
    else -> InkHue.study
}

/**
 * 时间尺度图：三种尺度三张不同的图，切换时左右滑入——
 * 今日 = 日晷（今天的计划落在 24 小时圆环上），本周 = 七日完成柱，本月 = 月历点阵。
 */
@Composable
private fun PeriodChart(period: String, report: StatsResponse?, plans: List<PlanSnapshotEntity>) {
    val hue = periodHue(period)
    val color = hue.color()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hue.tint()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
            Text(when (period) { "day" -> "  今日的安排"; "month" -> "  本月的节奏"; else -> "  本周的完成" },
                style = MaterialTheme.typography.titleMedium, color = InkTone.text)
            Spacer(Modifier.weight(1f))
            Text("完成 ${report?.completedCount ?: 0}", style = InkText.meta.copy(fontWeight = FontWeight.Bold), color = color)
        }
        when (period) {
            "day" -> DayDial(plans, report)
            "month" -> MonthGrid(report?.daily.orEmpty(), color)
            else -> WeekBars(normalizedWeek(report?.daily.orEmpty()), color)
        }
    }
}

@Composable
private fun DayDial(plans: List<PlanSnapshotEntity>, report: StatsResponse?) {
    val now = Calendar.getInstance()
    val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time) }
    val entries = remember(plans, todayKey) { DayTimeline.build(todayKey, plans, emptyList(), nowMinutes, collapseOverlaps = false) }
    val arcs = entries.map { entry ->
        val type = plans.firstOrNull { it.id == entry.id }?.type
        DialArc(entry.startMin.coerceAtLeast(0), entry.endMin.coerceAtMost(1440),
            (if (entry.state == TimelineState.RUNNING) InkHue.seal else InkHue.forType(type)).color(),
            emphasized = entry.state == TimelineState.RUNNING)
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        SundialDial(arcs, nowMinutes, Modifier.size(220.dp), ringWidth = 12.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${entries.size}", style = V2Tokens.numeralLarge.themed().copy(fontSize = 40.sp), color = InkTone.text)
                Text("项计划", style = InkText.meta, color = InkTone.soft)
            }
        }
    }
}

@Composable
private fun WeekBars(days: List<DailyStat>, color: androidx.compose.ui.graphics.Color) {
    val reduced = LocalReducedMotion.current
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(days) { if (!reduced) { grow.snapTo(0f); grow.animateTo(1f, tween(700, easing = Ink.Easing)) } }
    val max = (days.maxOfOrNull { it.completed } ?: 0).coerceAtLeast(1)
    val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) }
    Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom) {
        days.forEach { day ->
            val isToday = day.date == todayKey
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom) {
                if (day.completed > 0) Text("${day.completed}", style = InkText.meta.copy(fontSize = 10.sp), color = color)
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(((day.completed.toFloat() / max) * .78f * grow.value).coerceAtLeast(.03f))
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isToday) color else color.copy(alpha = .45f))
                )
                Text(weekdayLabel(day.date), style = InkText.meta.copy(fontSize = 10.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                    color = if (isToday) color else InkTone.faint, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MonthGrid(daily: List<DailyStat>, color: androidx.compose.ui.graphics.Color) {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_MONTH)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一开头
    val prefix = SimpleDateFormat("yyyy-MM-", Locale.US).format(cal.time)
    val byDay = daily.mapNotNull { d -> d.date?.takeIf { it.startsWith(prefix) }?.substring(8)?.toIntOrNull()?.let { it to d.completed } }.toMap()
    val max = (byDay.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, style = InkText.meta.copy(fontSize = 10.sp), color = InkTone.faint, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val cells = lead + daysInMonth
        (0 until (cells + 6) / 7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val day = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..daysInMonth) {
                            val done = byDay[day] ?: 0
                            val fill = if (done > 0) color.copy(alpha = .25f + .75f * done / max) else androidx.compose.ui.graphics.Color.Transparent
                            Box(
                                Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape).background(fill)
                                    .then(if (day == today) Modifier.border(1.5.dp, color, androidx.compose.foundation.shape.CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$day", style = InkText.meta.copy(fontSize = 11.sp),
                                    color = when { done > 0 && done * 2 >= max -> com.planlock.design.InkTone.onAccent
                                        day > today -> InkTone.faint.copy(alpha = .5f); else -> InkTone.soft })
                            }
                        }
                    }
                }
            }
        }
    }
}
