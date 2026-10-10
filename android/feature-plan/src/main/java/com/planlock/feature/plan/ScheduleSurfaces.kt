package com.planlock.feature.plan

import com.planlock.design.PickerField
import com.planlock.design.PlanDatePicker
import com.planlock.design.PlanTimePicker
import com.planlock.design.ChoiceChips
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
                Surface(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onPlan), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp)) { Text("计划"); Text("需要执行与进度追踪的时间块", style = MaterialTheme.typography.bodyMedium) }
                }
                Surface(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onSchedule), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp)) { Text("日程"); Text("事件、纪念日与提醒", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = onDismiss) { Text("取消") } },
    )
}

private data class RepeatOption(val label: String, val type: String?)

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
    val valid = title.isNotBlank() &&
        validStartDate != null &&
        validEndDate != null &&
        endDate >= startDate &&
        editDraft.timesValid(allDay) && editDraft.reminderValid && editDraft.ruleCanBeSaved

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (current == null) "新建日程" else "编辑日程") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (draft != null && (validStartDate == null || validEndDate == null)) item {
                    Text("日期尚未确定，请选择开始与结束日期后保存。", color = MaterialTheme.colorScheme.error)
                }
                if (draft?.needsTimeConfirmation == true && !allDay && (!CLOCK.matches(startTime) || !CLOCK.matches(endTime))) item {
                    Text("时刻尚未确定，请选择开始与结束时间，或选择全天。", color = MaterialTheme.colorScheme.error)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickerField("开始日期", startDate) { dateField = "start" }
                        PickerField("结束日期", endDate) { dateField = "end" }
                    }
                }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allDay, { allDay = it }); Text("全天") } }
                if (!allDay) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickerField("开始时间", startTime.ifEmpty { "未设置" }) { timeField = "start" }
                        PickerField("结束时间", endTime.ifEmpty { "未设置" }) { timeField = "end" }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("重复")
                        val preserved = "preserved"
                        val options = repeatOptions.map { it.type to it.label }
                        if (editDraft.showsPreservedRule) {
                            Text("原有重复规则暂不支持保存，请明确选择下方规则后再保存。", style = MaterialTheme.typography.bodySmall)
                        }
                        ChoiceChips(
                            if (editDraft.showsPreservedRule) listOf(preserved to "原有规则") + options else options,
                            if (editDraft.showsPreservedRule) preserved else repeatType,
                            { if (it != preserved) editDraft = editDraft.withRepeatType(it) },
                        )
                    }
                }
                item { OutlinedTextField(reminder, { editDraft = editDraft.withReminder(it.filter(Char::isDigit)) },
                    label = { Text("提前提醒（分钟）") }, placeholder = { Text("未设置") },
                    isError = !editDraft.reminderValid,
                    supportingText = { Text(if (editDraft.reminderValid) "留空表示不提醒" else "请输入有效的非负整数") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(location, { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth()) }
                if (plans.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("关联计划（仅作提示）")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = linkedPlanId == null, onClick = { linkedPlanId = null }, label = { Text("不关联") })
                            plans.take(2).forEach { plan ->
                                FilterChip(
                                    selected = linkedPlanId == plan.id,
                                    onClick = { linkedPlanId = plan.id },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(plan.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(modifier = Modifier.heightIn(min = 48.dp), enabled = valid && !saving, onClick = {
                onSave(editDraft.request(title.trim(), startDate, endDate, allDay, location, note, linkedPlanId, TimeZone.getDefault().id))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !saving, onClick = it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !saving, onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
internal fun TimelineCanvas(
    entries: List<TimelineEntry>,
    nowMinutes: Int,
    onPlan: (Long) -> Unit,
    onSchedule: (Long) -> Unit,
) {
    val window = remember(entries, nowMinutes) { DayTimeline.window(entries, nowMinutes) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${clock(window.startMin)}—${clock(window.endMin)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEach { entry ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { if (entry.kind == TimelineKind.PLAN) onPlan(entry.id) else onSchedule(entry.id) },
                shape = MaterialTheme.shapes.medium,
                color = if (entry.kind == TimelineKind.PLAN) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(DayTimeline.rangeLabel(entry), style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) { Text(entry.title); Text(entry.sourceLabel, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

private val CLOCK = Regex("([01]\\d|2[0-3]):[0-5]\\d")
private fun clock(minutes: Int): String = "%02d:%02d".format(Locale.US, minutes / 60, minutes % 60)
