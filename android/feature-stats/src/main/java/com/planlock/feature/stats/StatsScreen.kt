package com.planlock.feature.stats

import com.planlock.design.ChoiceChips
import com.planlock.design.SectionHeading
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.fillMaxSize
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
import com.planlock.app.api.dto.DailyStat
import com.planlock.app.api.dto.ExecutionRecordDto
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.ViolationAppStat
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.record.RecordStore
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.entity.BehaviorAggregateEntity
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.design.StatTile
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.V2Tokens
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

    LaunchedEffect(syncState.revision) { load() }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.STATS),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SyncRefreshHost(syncState.running, syncState.error, { sync.coordinator.refresh() },
            errorEventKey = "${com.planlock.app.api.TokenStore.userId}:${syncState.attempts}") { syncStatus ->
        when {
            loading -> {
                UIStatePanel(UiState.LOADING, "正在汇总本周数据", modifier = Modifier.padding(padding))
                androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 26.dp)) { syncStatus() }
            }
            reports.isEmpty() && behavior.isEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp)) {
                item { StatsStatusHeader(syncStatus) }
                item { ObservationNotice(observation) }
                item { UIStatePanel(if (readError != null) UiState.ERROR else UiState.EMPTY,
                    readError ?: "尚无统计快照，下拉同步后查看",
                    onAction = { scope.launch { load() }; sync.coordinator.refresh() }) }
                items(behavior) { item -> BehaviorRow(item, planNames[item.planId]) }
            }
            else -> {
                val value = reports[selectedPeriod]
                val presentation = reportPresentation(value, behavior, plans.mapTo(mutableSetOf()) { it.id })
                val ranking = presentation.ranking
                val violationDuration = presentation.durationMs
                val reportRows = value?.let {
                    planReportRows(plans, it.planExecution, scopeDates(selectedPeriod, OccurrenceDate.from(Calendar.getInstance())))
                }.orEmpty()
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        StatsStatusHeader(syncStatus)
                        ChoiceChips(listOf("day" to "今日", "week" to "本周", "month" to "本月"),
                            selectedPeriod, { selectedPeriod = it })
                        Text(
                            when {
                                offline || presentation.local -> localDataLabel(cachedAt)
                                cachedAt != null -> "更新于 ${formatClock(cachedAt!!)}"
                                else -> "尚未同步"
                            },
                            color = if (offline || presentation.local) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item { ObservationNotice(observation) }
                    readError?.let { error -> item { PersistentStatus("统计读取异常 · 详情", error) } }
                    item { DurationHero(violationDuration, ranking.sumOf { it.count }, ranking.size, periodLabel(selectedPeriod), presentation.local) }
                    item {
                        val earlyCount = value?.planExecution?.sumOf { it.earlyEndedCount } ?: 0
                        ReportTiles(value?.continuousDays ?: 0, value?.completedCount ?: 0,
                            earlyCount, periodLabel(selectedPeriod))
                    }
                    item { SectionTitle("分心来源排行", if (value == null) "本地累计" else scopeDateRangeLabel(selectedPeriod, OccurrenceDate.from(Calendar.getInstance()))) }
                    if (ranking.isEmpty()) item { Text("这段时间没有分心记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(ranking, key = { it.packageName }) { item -> ViolationRankRow(item) }
                    item { SectionTitle("完成情况", "计划时长 vs 实际时长") }
                    if (value == null) item { Text("离线快照未包含计划执行明细", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (reportRows.isEmpty()) item { UIStatePanel(UiState.EMPTY, "这段时间没有可统计的计划") }
                    else items(reportRows, key = { it.planId }) { PlanReportRowView(it) }
                    if (!value?.earlyEndings.isNullOrEmpty()) {
                        item { SectionTitle("提前结束", "最近 ${value!!.earlyEndings.size} 条") }
                        items(value!!.earlyEndings, key = { "${it.planId}-${it.at}-${it.method}" }) { ending ->
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(planNames[ending.planId] ?: "计划 #${ending.planId}", fontWeight = FontWeight.Bold)
                                    Text("${ending.at ?: "时间未知"} · ${ending.method ?: "主动结束（未要求密码确认）"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("旧版违规记录", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { legacyExpanded = !legacyExpanded }) { Text(if (legacyExpanded) "收起" else "查看") }
                                }
                                if (legacyExpanded) Text("${value!!.legacyViolationCount} 次 · v1.0 为被拦下的未遂尝试，只读展示，不计入上方时长与排行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { Text("带走你的数据", style = MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp)) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("选择适合用途的文件格式", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(enabled = exportState != ExportState.EXPORTING, onClick = { icsLauncher.launch("planlock-plans.ics") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (exportState == ExportState.EXPORTING) "导出中" else "导入日历 · ICS") }
                            Button(enabled = exportState != ExportState.EXPORTING, onClick = { csvLauncher.launch("planlock-records.csv") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (exportState == ExportState.EXPORTING) "导出中" else "用表格查看 · CSV") }
                            Button(enabled = exportState != ExportState.EXPORTING, onClick = { jsonLauncher.launch("planlock-records.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (exportState == ExportState.EXPORTING) "导出中" else "保存完整记录 · JSON") }
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
private fun DurationHero(durationMs: Long, count: Int, appCount: Int, scopeLabel: String, local: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.primary) {
        Box(Modifier.background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep))).padding(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (local) "本地累计分心时长（时:分:秒）" else "分心时长（时:分:秒）", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
                AdaptiveDuration(reportDurationLabel(durationMs))
                Text(
                    if (count == 0) "这段时间没有分心记录" else "${if (local) "本地累计" else scopeLabel}分心 $count 次 · 来自 $appCount 个应用",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = .82f),
                )
                if (local) Text("本地累计，未按当前时间范围筛选", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AdaptiveDuration(value: String) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val styles = listOf(V2Tokens.numeralHero, V2Tokens.numeralLarge, V2Tokens.numeralMedium,
            MaterialTheme.typography.bodyLarge)
        val style = styles.firstOrNull { measurer.measure(value, it, softWrap = false).size.width <= constraints.maxWidth }
            ?: styles.last()
        Text(value, style = style, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun ReportTiles(streak: Int, completed: Int, early: Int, period: String) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp || fontScale > 1.15f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedStatTile(streak, " 天", "连续达成", period, modifier = Modifier.fillMaxWidth())
                AnimatedStatTile(completed, label = "完成", trend = period, modifier = Modifier.fillMaxWidth())
                AnimatedStatTile(early, label = "提前结束", trend = period, danger = early > 0, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedStatTile(streak, " 天", "连续达成", period, modifier = Modifier.weight(1f).fillMaxHeight())
                AnimatedStatTile(completed, label = "完成", trend = period, modifier = Modifier.weight(1f).fillMaxHeight())
                AnimatedStatTile(early, label = "提前结束", trend = period, danger = early > 0, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    SectionHeading(title, detail)
}

@Composable
private fun ViolationRankRow(item: ViolationAppStat) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.appName?.takeIf { it.isNotBlank() } ?: item.packageName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${item.count} 次${if (item.approxDurationMs > 0) " · 含近似" else " · 精确记录"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(reportDurationLabel(item.totalDurationMs), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlanReportRowView(item: PlanReportRow) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("计划 ${reportDurationLabel(item.plannedDurationMs)} · 实际 ${reportDurationLabel(item.actualDurationMs)}", color = MaterialTheme.colorScheme.primary)
            Text("完成 ${item.completedCount} · 提前结束 ${item.earlyEndedCount} · 未执行 ${item.missedCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(item.packageName, style = MaterialTheme.typography.titleMedium)
                Text("${planName ?: "计划 #${item.planId}"} · $source · ${item.occurrenceCount} 次", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatDuration(item.totalDurationMs), color = MaterialTheme.colorScheme.primary)
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
        Box(Modifier.background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep))).padding(22.dp)) {
            Column {
                Text("本周", style = V2Tokens.labelMicro, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$streak", style = V2Tokens.numeralHero, color = androidx.compose.ui.graphics.Color.White)
                    Text(" 天连续", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .82f), modifier = Modifier.padding(start = 8.dp, bottom = 9.dp))
                }
                Text("本周完成 $completed 次 · 每一次完成都会留下刻度", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f))
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
    modifier: Modifier = Modifier
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val animatedValue by animateIntAsState(targetValue = if (started) value else 0, animationSpec = tween(450), label = "stat-number")
    StatTile("$animatedValue$suffix", label, trend, danger, modifier)
}

@Composable
private fun WeekChart(daily: List<DailyStat>, onBrand: Boolean = false) {
    val growth = remember { Animatable(0f) }
    LaunchedEffect(daily) { growth.snapTo(0f); growth.animateTo(1f, tween(500)) }
    val primary = if (onBrand) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.primary
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
                daily.forEach { Text(weekdayLabel(it.date), style = MaterialTheme.typography.labelMedium, color = if (onBrand) androidx.compose.ui.graphics.Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
private fun ExecutionRow(record: ExecutionRecordDto, planName: String?) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(planName ?: "计划 #${record.planId}", style = MaterialTheme.typography.titleMedium); Text(record.startedAt ?: "未记录开始时间", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(executionStatusLabel(record.status), color = MaterialTheme.colorScheme.primary)
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
    Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("执行报告", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f))
        syncStatus()
    }
}
