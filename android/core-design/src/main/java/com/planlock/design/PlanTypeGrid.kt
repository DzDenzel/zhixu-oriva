package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 计划类型网格（v4.4，U6；负责人 2026-10-19「类型多了但没有之前的颜色框了」）：
 * 每种类型一格、带自己的类别色——未选中是同色淡底 + 同色字，选中是实心类别色 + 反白字。
 * 一行 4 格（8 个预设正好两行），自定义类型接在后面；名字过长省略。颜色键来自 PlanTypes（study/work/sport/sleep/rose/amber）。
 * v4.5（U6 收尾，续派 U-c）：最后一格「+ 自定义」（虚线框）打开 [PlanTypeEditorSheet]；长按自定义类型可改名 / 换色 / 删除。
 */

data class PlanTypeChoice(val key: String, val name: String, val colorKey: String)

/** 颜色键 → 类别色；未知键按名称散列（同名同色）。 */
fun InkHue.forColorKey(colorKey: String?, fallbackName: String? = null): InkHue.Hue = when (colorKey) {
    "study" -> study
    "work" -> work
    "sport" -> sport
    "sleep" -> sleep
    "rose" -> rose
    "amber" -> amber
    "hobby" -> hobby
    "other" -> other
    else -> forType(fallbackName)
}

/** 「+ 自定义」格在网格里的占位键。 */
private const val ADD_CUSTOM_KEY = "\u0000add"

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlanTypeGrid(
    choices: List<PlanTypeChoice>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    columns: Int = 4,
    onAddCustom: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
) {
    val cells = if (onAddCustom != null) choices + PlanTypeChoice(ADD_CUSTOM_KEY, "自定义", "") else choices
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { choice ->
                    if (choice.key == ADD_CUSTOM_KEY && onAddCustom != null) {
                        AddCustomCell(onAddCustom, Modifier.weight(1f))
                        return@forEach
                    }
                    val hue = InkHue.forColorKey(choice.colorKey, choice.name).color()
                    val isSelected = choice.key == selectedKey
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .clip(shape)
                            .background(if (isSelected) hue else hue.inkTint())
                            .then(if (isSelected) Modifier else Modifier.border(1.dp, hue.copy(alpha = .28f), shape))
                            .then(
                                if (onEdit != null && choice.key.startsWith("custom:"))
                                    Modifier.combinedClickable(role = Role.RadioButton, onLongClickLabel = "编辑类型",
                                        onLongClick = { onEdit(choice.key) }) { onSelect(choice.key) }
                                else Modifier.clickable(role = Role.RadioButton) { onSelect(choice.key) })
                            .semantics { selected = isSelected; contentDescription = choice.name + if (isSelected) "，已选中" else "" }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(choice.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) InkTone.onAccent else hue)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AddCustomCell(onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val line = InkTone.soft
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .drawBehind {
                drawRoundRect(line.copy(alpha = .55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            .clickable(role = Role.Button, onClickLabel = "新建自定义类型", onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        // 窄屏 + 大字体时一格只有约 56dp：图标随字缩小，文字按宽度缩字，不截成「+ …」
        androidx.compose.material3.Icon(Icons.Filled.Add, null, tint = InkTone.soft,
            modifier = Modifier.size(14.dp))
        FitText("自定义", MaterialTheme.typography.labelLarge, InkTone.soft, minSize = 9.sp)
    }
}
