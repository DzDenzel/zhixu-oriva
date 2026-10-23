package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * 自定义计划类型面板（v4.5，CHANGE-060 C2，续派 U-c）：底部面板——名称（≤ 6 字）+ 类别色色板 + 预览格。
 * 新建：「保存」；编辑：「保存」+ 底部「删除类型」（先确认，说明已用它的计划会归入「其他」）。
 * 色板来自类型目录的颜色键（codex B6 扩到 8 色后自动多出两格）。
 */

/** 自定义类型名的上限（CHANGE-060 C2：≤ 6 个字，按 Unicode 字符计）。 */
const val PLAN_TYPE_NAME_MAX = 6

/** 名称校验：返回给人看的一句话；可用时 null。[existing] 为其他类型（不含正在编辑的这个）的名称。 */
fun planTypeNameIssue(name: String, existing: Collection<String>): String? {
    val trimmed = name.trim()
    val length = trimmed.codePointCount(0, trimmed.length)
    return when {
        length == 0 -> "请填写类型名称"
        length > PLAN_TYPE_NAME_MAX -> "名称最多 $PLAN_TYPE_NAME_MAX 个字"
        existing.any { it.trim() == trimmed } -> "已有同名类型"
        else -> null
    }
}

/** 删除确认的正文：「已有 3 个计划用了这个类型，删除后会归入『其他』。」 */
fun planTypeDeleteDetail(usage: Int): String =
    if (usage > 0) "已有 $usage 个计划用了这个类型，删除后会归入「其他」。" else "还没有计划用这个类型。"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanTypeEditorSheet(
    initialName: String,
    initialColor: String,
    colorKeys: List<String>,
    existingNames: Collection<String>,
    usage: Int?,
    onSave: (name: String, color: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor.takeIf { it in colorKeys } ?: colorKeys.first()) }
    var tried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val issue = planTypeNameIssue(name, existingNames)
    val editing = onDelete != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = InkTone.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (editing) "编辑类型" else "新建类型", style = MaterialTheme.typography.titleLarge, color = InkTone.text,
                modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = name,
                onValueChange = { v -> if (v.codePointCount(0, v.length) <= PLAN_TYPE_NAME_MAX + 2) name = v },
                label = { Text("类型名称") },
                placeholder = { Text("如：练琴、家务") },
                singleLine = true,
                isError = tried && issue != null,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text(if (tried && issue != null) issue else "最多 $PLAN_TYPE_NAME_MAX 个字", Modifier.weight(1f))
                        Text("${name.trim().let { it.codePointCount(0, it.length) }} / $PLAN_TYPE_NAME_MAX")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("颜色", style = InkText.caption, color = InkTone.soft)
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                colorKeys.forEach { key ->
                    val hue = InkHue.forColorKey(key).color()
                    val chosen = key == color
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.RadioButton) { color = key }
                            .semantics { selected = chosen; contentDescription = colorName(key) + if (chosen) "，已选中" else "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(hue)
                            .then(if (chosen) Modifier.border(2.dp, InkTone.text, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            if (chosen) Icon(Icons.Filled.Check, null, tint = InkTone.onAccent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            // 预览：和类型网格里选中时一样
            val previewHue = InkHue.forColorKey(color).color()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("预览", style = InkText.caption, color = InkTone.soft)
                Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(previewHue).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center) {
                    Text(name.trim().ifEmpty { "类型" }, style = MaterialTheme.typography.labelLarge, color = InkTone.onAccent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = { tried = true; if (issue == null) onSave(name.trim(), color) },
                shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (editing) "保存" else "添加并选用")
            }
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("删除类型", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete && onDelete != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除「${initialName.trim()}」？") },
        text = { Text(planTypeDeleteDetail(usage ?: 0)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

private fun colorName(key: String): String = when (key) {
    "study" -> "靛蓝"; "work" -> "黛青"; "sport" -> "草绿"; "sleep" -> "藤紫"; "rose" -> "桃红"; "amber" -> "琥珀"
    "hobby" -> "赭橙"; "other" -> "岩灰"
    else -> key
}
