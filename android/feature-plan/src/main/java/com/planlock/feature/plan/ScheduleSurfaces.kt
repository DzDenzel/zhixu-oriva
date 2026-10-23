package com.planlock.feature.plan

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.planlock.design.InkFullScreenPage
import com.planlock.design.PresentationCard
import com.planlock.design.SettingSwitch
import com.planlock.design.PickerField
import com.planlock.design.PlanDatePicker
import com.planlock.design.PlanTimePicker
import com.planlock.design.ChoiceChips
import com.planlock.design.inkFormField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.planlock.app.api.dto.ScheduleUpsertRequest
import com.planlock.app.api.AssistantScheduleDraft
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.TimelineEntry
import com.planlock.app.record.TimelineKind
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun CreateActionSheet(onDismiss: () -> Unit, onPlan: () -> Unit, onSchedule: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CreateChoice("行", "计划", "需要执行与进度追踪的时间块", com.planlock.design.InkTone.slab, onPlan)
                CreateChoice("记", "日程", "事件、纪念日与提醒", com.planlock.design.InkTone.brass, onSchedule)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreateChoice(glyph: String, title: String, detail: String, seal: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = com.planlock.design.InkTone.raised,
        border = androidx.compose.foundation.BorderStroke(1.dp, com.planlock.design.InkTone.hairline),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            com.planlock.design.InkSeal(glyph, sealSize = 42.dp, color = seal)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 删除确认的说明：重复日程说明所有重复一起删除；有提醒的说明提醒一并取消。 */
internal fun scheduleDeleteDetail(schedule: ScheduleSnapshotEntity): String = buildString {
    append(if (schedule.repeatRule.isNullOrBlank()) "这条日程会被删除" else "这是一条重复日程，每一次都会一起删除")
    append(if (schedule.remindAdvanceMin != null) "，它的提醒也会取消。" else "。")
    append("删除后不能撤销。")
}

private data class RepeatOption(val label: String, val type: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorSheet(
    current: ScheduleSnapshotEntity?,
    plans: List<PlanSnapshotEntity>,
    onDismiss: () -> Unit,
    onSave: (ScheduleUpsertRequest) -> Unit,
    onDelete: (() -> Unit)?,
    draft: AssistantScheduleDraft? = null,
    saving: Boolean = false,
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date()) }
    var title by remember(current?.id, draft) { mutableStateOf(current?.title ?: draft?.title.orEmpty()) }
    // An unresolved assistant date is intentionally blank, not silently replaced with today.
    val initialDate = current?.startDate ?: if (draft != null) draft.startDate.orEmpty() else today
    var startDate by remember(current?.id, draft) { mutableStateOf(initialDate) }
    var endDate by remember(current?.id, draft) { mutableStateOf(current?.endDate ?: draft?.endDate ?: initialDate) }
    var editDraft by remember(current?.id, draft) { mutableStateOf(ScheduleEditDraft.fromSnapshot(current, draft)) }
    val startTime = editDraft.startTime
    val endTime = editDraft.endTime
    val reminder = editDraft.reminder
    val repeatType = editDraft.repeatType
    var allDay by remember(current?.id, draft) { mutableStateOf(current?.allDay ?: draft?.allDay ?: false) }
    var location by remember(current?.id) { mutableStateOf(current?.location.orEmpty()) }
    var selectedPlace by remember(current?.id) { mutableStateOf(current?.let { row ->
        if (row.locationLat != null && row.locationLon != null && com.planlock.app.api.validCoordinates(row.locationLat,row.locationLon))
            com.planlock.app.api.PlaceSelection(row.location.orEmpty().ifBlank { "已选地点" },requireNotNull(row.locationLat),requireNotNull(row.locationLon)) else null
    }) }
    var note by remember(current?.id) { mutableStateOf(current?.note.orEmpty()) }
    var linkedPlanId by remember(current?.id) { mutableStateOf(current?.linkedPlanId) }
    val repeatOptions = remember { listOf(RepeatOption("单次", null), RepeatOption("每天", "daily"), RepeatOption("每周", "weekly"), RepeatOption("每月", "monthly"), RepeatOption("每年", "yearly")) }
    var dateField by remember { mutableStateOf<String?>(null) }
    var timeField by remember { mutableStateOf<String?>(null) }
    dateField?.let { field ->
        PlanDatePicker(if (field == "start") "开始日期" else "结束日期",
            if (field == "start") startDate else endDate,
            onConfirm = { if (field == "start") startDate = it else endDate = it; dateField = null },
            onDismiss = { dateField = null })
    }
    timeField?.let { field ->
        val value = if (field == "start") startTime else endTime
        PlanTimePicker(title = if (field == "start") "开始时间" else "结束时间",
            initialHour = value.substringBefore(":").toIntOrNull() ?: if (field == "start") 9 else 10,
            initialMinute = value.substringAfter(":", "").toIntOrNull() ?: 0,
            onConfirm = { hour, minute ->
                val selected = "%02d:%02d".format(Locale.US, hour, minute)
                editDraft = if (field == "start") editDraft.withStartTime(selected) else editDraft.withEndTime(selected)
                timeField = null
            }, onDismiss = { timeField = null })
    }
    val validStartDate = OccurrenceDate.parseIso(startDate)
    val validEndDate = OccurrenceDate.parseIso(endDate)
    // BUG-112（v4.4）：保存栏贴底；点保存先校验，不通过不弹窗——滚到并聚焦第一项，保存栏上方一行提醒。
    val issues = scheduleFormIssues(title, startDate, endDate, allDay, editDraft)
    val targets = com.planlock.design.rememberInkFormTargets()
    var attempted by remember(current?.id, draft) { mutableStateOf(false) }
    val shownIssues = if (attempted) issues else emptyList()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val capabilityGate = com.planlock.design.rememberCapabilityPermissionGate()
    val revealFirst: () -> Unit = { issues.firstOrNull()?.let { scope.launch { targets.reveal(it.field) } } }
    val submit: () -> Unit = {
        attempted = true
        if (issues.isNotEmpty()) {
            revealFirst()
        } else {
            capabilityGate.beforeSave(com.planlock.app.security.CapabilityRequirements.reminder(reminder.isNotEmpty())) {
                val request = editDraft.request(title.trim(), startDate, endDate, allDay, location, note, linkedPlanId, TimeZone.getDefault().id)
                onSave(selectedPlace?.let { request.copy(locationLat=it.latitude,locationLon=it.longitude) } ?: request)
            }
        }
    }

    // v4.2（UI 审查 P1）：删除先确认，标题写清删的是哪一条；取消 / 点外面 = 不删。
    var confirmDelete by remember(current?.id) { mutableStateOf(false) }

    // v4.1：与「新建计划」一致的全屏编辑页，而不是弹窗。
    InkFullScreenPage(onDismissRequest = { if (!saving) onDismiss() }) {
        if (confirmDelete && onDelete != null && current != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除「${current.title}」？") },
                text = { Text(scheduleDeleteDetail(current)) },
                confirmButton = {
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { confirmDelete = false; onDelete() }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { confirmDelete = false }) { Text("取消") }
                },
            )
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                title = { Text(if (current == null) "新建日程" else "编辑日程", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(enabled = !saving, onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    onDelete?.let {
                        TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !saving, onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("日程标题") },
                    modifier = Modifier.fillMaxWidth().inkFormField(targets, ScheduleField.TITLE, focusable = true, cornerRadius = 6.dp), singleLine = true,
                    isError = shownIssues.messageFor(ScheduleField.TITLE) != null,
                    supportingText = shownIssues.messageFor(ScheduleField.TITLE)?.let { { Text(it) } })
                if (draft != null && (validStartDate == null || validEndDate == null)) {
                    Text("日期尚未确定，请选择开始与结束日期后保存。", color = MaterialTheme.colorScheme.error)
                }
                if (draft?.needsTimeConfirmation == true && !allDay && (!CLOCK.matches(startTime) || !CLOCK.matches(endTime))) {
                    Text("时刻尚未确定，请选择开始与结束时间，或选择全天。", color = MaterialTheme.colorScheme.error)
                }
                ScheduleSection("日期", Modifier.inkFormField(targets, ScheduleField.DATE, cornerRadius = 18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField("开始", startDate, Modifier.weight(1f)) { dateField = "start" }
                        PickerField("结束", endDate, Modifier.weight(1f)) { dateField = "end" }
                    }
                    // 结束早于开始：随时提示（不必等点保存）
                    if (validStartDate != null && validEndDate != null && endDate < startDate) {
                        Text("结束日期不能早于开始日期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else shownIssues.messageFor(ScheduleField.DATE)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    SettingSwitch("全天", "不设具体时刻，整天显示在日程里", allDay) { allDay = it }
                }
                if (!allDay) ScheduleSection("时间", Modifier.inkFormField(targets, ScheduleField.TIME, cornerRadius = 18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField("开始", startTime.ifEmpty { "未设置" }, Modifier.weight(1f)) { timeField = "start" }
                        PickerField("结束", endTime.ifEmpty { "未设置" }, Modifier.weight(1f)) { timeField = "end" }
                    }
                    shownIssues.messageFor(ScheduleField.TIME)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                ScheduleSection("重复", Modifier.inkFormField(targets, ScheduleField.REPEAT, cornerRadius = 18.dp)) {
                    val preserved = "preserved"
                    val options = repeatOptions.map { it.type to it.label }
                    if (editDraft.showsPreservedRule) {
                        Text("原有重复规则暂不支持保存，请明确选择下方规则后再保存。", style = MaterialTheme.typography.bodySmall,
                            color = if (shownIssues.messageFor(ScheduleField.REPEAT) != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                    ChoiceChips(
                        if (editDraft.showsPreservedRule) listOf(preserved to "原有规则") + options else options,
                        if (editDraft.showsPreservedRule) preserved else repeatType,
                        { if (it != preserved) editDraft = editDraft.withRepeatType(it) },
                    )
                }
                ScheduleSection("提醒") {
                    OutlinedTextField(reminder, { editDraft = editDraft.withReminder(it.filter(Char::isDigit)) },
                        label = { Text("提前提醒（分钟）") }, placeholder = { Text("未设置") },
                        isError = !editDraft.reminderValid,
                        supportingText = { Text(if (editDraft.reminderValid) "留空表示不提醒" else "请输入有效的非负整数") },
                        modifier = Modifier.fillMaxWidth().inkFormField(targets, ScheduleField.REMINDER, focusable = true, cornerRadius = 6.dp), singleLine = true)
                }
                ScheduleSection("地点与备注") {
                    // U5（v4.3）：定位标 → 授权定位 → 地图选点；输入时联想附近地点
                    PlaceField(location, selectedPlace,
                        onText = { location = it; selectedPlace = null },
                        onPlace = { selectedPlace = it; location = it.name })
                    OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
                }
                if (plans.isNotEmpty()) ScheduleSection("关联计划（仅作提示）") {
                    ChoiceChips(
                        listOf<Pair<Long?, String>>(null to "不关联") + plans.take(6).map { it.id to it.name },
                        linkedPlanId,
                        { linkedPlanId = it },
                    )
                }
            }
            com.planlock.design.InkSaveBar("保存日程", { submit() }, busy = saving,
                hint = com.planlock.design.saveBarHint(shownIssues), onHint = revealFirst)
        }
    }
}

@Composable
private fun ScheduleSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PresentationCard(modifier) {
        Text(title, style = com.planlock.design.InkText.meta, color = com.planlock.design.InkTone.faint)
        content()
    }
}

@Composable
internal fun TimelineCanvas(
    entries: List<TimelineEntry>,
    nowMinutes: Int,
    onPlan: (Long) -> Unit,
    onSchedule: (Long) -> Unit,
) {
    val window = remember(entries, nowMinutes) { DayTimeline.window(entries, nowMinutes) }
    val tone = com.planlock.design.InkTone
    val line = tone.hairline
    Column(Modifier.fillMaxWidth()) {
        Text("${clock(window.startMin)} — ${clock(window.endMin)}", style = com.planlock.design.InkText.eyebrow,
            color = tone.faint, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        entries.forEachIndexed { index, entry ->
            val running = entry.state == com.planlock.app.record.TimelineState.RUNNING
            val color = when (entry.state) {
                com.planlock.app.record.TimelineState.RUNNING -> tone.vermilion
                com.planlock.app.record.TimelineState.UPCOMING -> if (entry.kind == TimelineKind.PLAN) tone.text else tone.brass
                com.planlock.app.record.TimelineState.ENDED -> tone.faint
                com.planlock.app.record.TimelineState.PAUSED -> com.planlock.design.Ink.stone
            }
            Row(
                Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                    .clickable { if (entry.kind == TimelineKind.PLAN) onPlan(entry.id) else onSchedule(entry.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clock(entry.startMin.coerceIn(0, 1439)), style = com.planlock.design.InkText.meta,
                    color = if (running) color else tone.soft, modifier = Modifier.widthIn(min = 48.dp).padding(end = 4.dp))
                Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Box(Modifier.size(if (running) 10.dp else 8.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
                Surface(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (running) tone.vermilion.copy(alpha = .08f) else tone.raised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (running) color.copy(alpha = .4f) else line),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(30.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(DayTimeline.rangeLabel(entry) + " · " + entry.sourceLabel, style = com.planlock.design.InkText.meta.copy(fontSize = 11.sp),
                                color = tone.faint)
                        }
                    }
                }
            }
            if (index == entries.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

private val CLOCK = Regex("([01]\\d|2[0-3]):[0-5]\\d")
private fun clock(minutes: Int): String = "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)
