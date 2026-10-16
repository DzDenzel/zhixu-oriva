package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 设置子页的统一构件（v4.1）。与设置总览同一套几何：
 * 印章方块（一个字 + 类别色淡底）· 标题 · 一行元信息 · 右侧状态签 / 开关 / 箭头。
 * 子页顶部是一张「题签」：大印章 + 标题 + 当前状态，进页即知道这一栏现在是什么状态。
 */

/** 印章方块：一个字，类别色淡底。 */
@Composable
fun SettingsGlyph(glyph: String, hue: InkHue.Hue, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).background(hue.tint(), RoundedCornerShape(size * .31f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = InkTone.display, fontWeight = FontWeight.Black,
            fontSize = (size.value * .44f).sp, color = hue.color())
    }
}

/** 子页题签：大印章 + 标题 + 状态签 + 一行说明。 */
@Composable
fun SettingsHero(
    glyph: String,
    hue: InkHue.Hue,
    title: String,
    status: String? = null,
    statusOn: Boolean = true,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsGlyph(glyph, hue, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = InkText.masthead.copy(fontSize = 24.sp, lineHeight = 30.sp), color = InkTone.text)
            if (status != null || detail != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status?.let { SettingsStateTag(it, statusOn) }
                    detail?.let {
                        Text(it, style = InkText.meta.copy(fontSize = 11.sp, lineHeight = 15.sp), color = InkTone.faint,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
    }
}

/** 分组小标题（淡墨元信息字）。 */
@Composable
fun SettingsCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = InkText.meta, color = InkTone.faint, modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp))
}

/** 一组设置：抬起的纸面，组内行之间用内缩发丝线分隔（用 [SettingsDivider]）。 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = InkTone.raised) {
        Column(content = content)
    }
}

@Composable
fun SettingsDivider(inset: Dp = 62.dp) {
    HorizontalDivider(Modifier.padding(start = inset, end = 16.dp), color = InkTone.hairline.copy(alpha = .6f))
}

/**
 * 一行设置。glyph 为 null 时不画印章（纯文字行）。
 * trailing 为 null 时：可点击就显示箭头；传入状态签或开关时替换箭头。
 */
@Composable
fun SettingsItem(
    title: String,
    detail: String? = null,
    glyph: String? = null,
    hue: InkHue.Hue = InkHue.study,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).semantics { role = Role.Button } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        glyph?.let { SettingsGlyph(it, hue) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: InkTone.text)
            detail?.let { Text(it, style = InkText.meta.copy(fontSize = 11.sp, lineHeight = 15.sp), color = InkTone.faint) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> SettingsChevron()
        }
    }
}

/** 带开关的一行；整行可点。 */
@Composable
fun SettingsSwitchItem(
    title: String,
    detail: String? = null,
    checked: Boolean,
    glyph: String? = null,
    hue: InkHue.Hue = InkHue.study,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val controls = LocalSecondaryControlColors.current
    SettingsItem(
        title = title, detail = detail, glyph = glyph, hue = hue,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        trailing = {
            Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = controls.onAccent, checkedTrackColor = controls.accent))
        },
    )
}

@Composable
fun SettingsChevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = InkTone.faint)
}

/** 状态签：开 = 草绿底 + 实心点；未开 = 琥珀底 + 空心圈。 */
@Composable
fun SettingsStateTag(text: String, on: Boolean) {
    val hue = if (on) InkHue.sport else InkHue.amber
    val tone = hue.color()
    Row(
        Modifier.background(hue.tint(), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            if (on) Modifier.size(6.dp).background(tone, CircleShape)
            else Modifier.size(6.dp).border(1.2.dp, tone, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(text, style = InkText.meta.copy(fontSize = 11.sp, lineHeight = 14.sp), color = tone, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
