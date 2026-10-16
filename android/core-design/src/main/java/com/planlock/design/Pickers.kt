package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

val StandardAppCategories = listOf("游戏", "短视频", "社交", "影音", "资讯", "购物", "学习", "工具")

internal fun pickerVisibleApps(apps: List<AppOption>): List<AppOption> = apps.filterNot { it.id.startsWith("category:") }
    .map { if (it.category in StandardAppCategories) it else it.copy(category = "工具") }

internal fun bulkSelection(apps: List<AppOption>, category: String?): List<AppOption> =
    apps.filter { !it.id.startsWith("category:") && (category == null || it.category == category) }

internal data class BulkSelectionAction(val apps: List<AppOption>, val select: Boolean)

internal fun bulkSelectionAction(
    apps: List<AppOption>,
    category: String?,
    selectedIds: Set<String>,
): BulkSelectionAction {
    val target = bulkSelection(apps, category)
    val allSelected = target.isNotEmpty() && target.all { it.id in selectedIds }
    return BulkSelectionAction(target, select = !allSelected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    title: String,
    apps: List<AppOption>,
    selectedIds: Set<String>,
    lockedIds: Set<String> = emptySet(),
    onToggle: (AppOption) -> Unit,
    onQuickAdd: (List<AppOption>) -> Unit,
    onDismiss: () -> Unit,
    singleSelection: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var showBulkHint by remember { mutableStateOf(false) }
    val selectableApps = pickerVisibleApps(apps)
    val filtered = selectableApps.filter {
        (query.isBlank() || it.name.contains(query, true)) && (category == null || it.category == category)
    }
    fun applyBulkAction(target: String?) {
        val action = bulkSelectionAction(apps, target, selectedIds)
        if (action.apps.isEmpty()) return
        if (action.select) {
            onQuickAdd(action.apps)
            showBulkHint = true
        } else {
            action.apps.filter { it.id in selectedIds }.forEach(onToggle)
        }
    }
    val currentBulkAction = bulkSelectionAction(apps, category, selectedIds)
    val controls = LocalSecondaryControlColors.current
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = controls.container,
        selectedLabelColor = controls.onContainer,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索已安装应用") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("全部") }, colors = chipColors) }
                items(StandardAppCategories) { item ->
                    val categoryApps = selectableApps.filter { it.category == item }
                    val selectedCount = categoryApps.count { it.id in selectedIds }
                    FilterChip(
                        selected = category == item,
                        onClick = { category = item },
                        label = { Text("$item $selectedCount/${categoryApps.size}") },
                        colors = chipColors,
                    )
                }
            }
            if (selectedIds.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps.filter { it.id in selectedIds }) { app ->
                        val locked = app.id in lockedIds
                        AssistChip(
                            onClick = { onToggle(app) },
                            modifier = Modifier.alpha(if (locked) .48f else 1f),
                            label = { Text(app.name) },
                            leadingIcon = if (locked) { { Icon(Icons.Filled.Lock, "已锁定", Modifier.size(18.dp)) } } else null,
                            trailingIcon = { Icon(Icons.Filled.Close, "移除", Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            if (!singleSelection) Button(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                onClick = { applyBulkAction(category) },
                enabled = currentBulkAction.apps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(if (currentBulkAction.select) Icons.Filled.Add else Icons.Filled.Remove, null)
                Spacer(Modifier.width(8.dp))
                Text(when {
                    currentBulkAction.select && category == null -> "快速添加全部已安装应用"
                    currentBulkAction.select -> "快速添加${category}应用"
                    category == null -> "取消选择全部应用"
                    else -> "取消选择${category}应用"
                })
            }
            if (showBulkHint) Text(
                "批量选择可能不全面，部分应用可能不在分类中，可搜索或手动补充",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                if (filtered.isEmpty()) item { Text("没有找到可见的已安装应用", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (category == null) {
                    StandardAppCategories.forEach { section ->
                        val sectionApps = filtered.filter { it.category == section }
                        if (sectionApps.isNotEmpty()) {
                            item(key = "section-$section") {
                                val sectionAction = bulkSelectionAction(apps, section, selectedIds)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(section, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    if (!singleSelection) TextButton(onClick = { applyBulkAction(section) }) {
                                        Text(if (sectionAction.select) "快速添加该分类" else "取消选择该分类")
                                    }
                                }
                            }
                            items(sectionApps, key = { it.id }) { app -> AppRow(app, app.id in selectedIds, app.id in lockedIds) { onToggle(app) } }
                            item(key = "divider-$section") { HorizontalDivider() }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { app -> AppRow(app, app.id in selectedIds, app.id in lockedIds) { onToggle(app) } }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanTimePicker(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    crossDayHint: String? = null,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.15f
    var inputMode by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                if (!compact) IconButton(onClick = { inputMode = !inputMode }, modifier = Modifier.size(48.dp)) {
                    Icon(if (inputMode) Icons.Filled.Schedule else Icons.Filled.Keyboard, if (inputMode) "切换钟面" else "切换键盘")
                }
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (compact || inputMode) TimeInput(state) else TimePicker(state)
                crossDayHint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }, modifier = Modifier.height(48.dp)) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("取消") } }
    )
}

enum class RepeatPreset(val label: String, val apiType: String) {
    DAILY("每天", "daily"), WEEKDAY("工作日", "weekly"), WEEKEND("周末", "weekly"), CUSTOM("自定义", "weekly")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatRuleSelector(preset: RepeatPreset, weekdays: Set<Int>, onPreset: (RepeatPreset) -> Unit, onWeekday: (Int) -> Unit) {
    val controls = LocalSecondaryControlColors.current
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = controls.container, selectedLabelColor = controls.onContainer)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(RepeatPreset.entries.map { it to it.label }, preset, onPreset)
        if (preset == RepeatPreset.CUSTOM) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                    FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = index + 1 in weekdays, onClick = { onWeekday(index + 1) }, label = { Text(label) }, colors = chipColors)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanTypeSegmented(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("学习", "工作", "运动", "睡眠")
    // 每种类型一种颜色：选中时实心类别色，未选中是同色淡底
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val hue = InkHue.forType(option).color()
            val isSelected = option == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(if (isSelected) hue else hue.inkTint())
                    .clickable { onSelect(option) }
                    .semantics { contentDescription = option + if (isSelected) "，已选中" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) com.planlock.design.InkTone.onAccent else hue)
            }
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val controls = LocalSecondaryControlColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = controls.onAccent, checkedTrackColor = controls.accent),
        )
    }
}

@Composable
fun ReminderStepper(value: Int, onValueChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("提前提醒", style = MaterialTheme.typography.titleMedium)
            Text("调整步进：± 5 分钟", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onValueChange((value - 5).coerceAtLeast(0)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Remove, "减少 5 分钟") }
        Text("$value 分", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onValueChange((value + 5).coerceAtMost(120)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "增加 5 分钟") }
    }
}
