package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.planlock.app.security.Capability

/*
 * 权限说明面板（v4.5，BUG-111 / 续派 U-b）：用到某项能力时从底部升起——图标 + 「开启 X」+ 一句做什么，
 * 下面两行「为什么需要」「不开启的话」，主按钮「去开启」，文字按钮「暂不」。
 * 「为什么需要」直接用 Capability.explanation（数据层的口径），界面只补「做什么」「不开启的话」两句。
 */

data class CapabilityCopy(val icon: ImageVector, val does: String, val without: String)

fun capabilityCopy(capability: Capability): CapabilityCopy = when (capability) {
    Capability.DND -> CapabilityCopy(Icons.Filled.DoNotDisturbOn,
        "执行计划时自动打开勿扰，只让闹钟响。", "计划照常执行，只是手机不会自动静音。")
    Capability.NOTIFICATION_SILENCE -> CapabilityCopy(Icons.Filled.NotificationsOff,
        "执行计划时减少其他应用的通知声音。", "计划照常执行，其他应用的通知照常响。")
    Capability.NOTIFICATIONS -> CapabilityCopy(Icons.Filled.Notifications,
        "在通知栏提醒你计划和日程。", "收不到提醒，只能打开知序查看。")
    Capability.EXACT_ALARM -> CapabilityCopy(Icons.Filled.Alarm,
        "让提醒在你设定的时刻准时出现。", "提醒仍会出现，但可能晚几分钟。")
    Capability.LOCATION -> CapabilityCopy(Icons.Filled.NearMe,
        "用你所在的位置显示天气、把地图定位到你身边。", "可以手动选城市，或在地图上拖动选点。")
}

/** 开关下方的一行琥珀小字：「未授权，此项不会生效」，点它重新打开说明面板。 */
@Composable
fun CapabilityDeniedNote(onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val amber = InkHue.amber.color()
    Row(
        modifier.then(if (onClick != null) Modifier.heightIn(min = 40.dp).clickableNote(onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Info, null, tint = amber, modifier = Modifier.size(16.dp))
        Text("未授权，此项不会生效", style = InkText.caption, color = amber, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Text("去开启", style = InkText.caption, color = amber)
    }
}

private fun Modifier.clickableNote(onClick: () -> Unit): Modifier =
    this.clickable(onClickLabel = "去开启", role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityPermissionSheet(capability: Capability, onOpen: () -> Unit, onLater: () -> Unit) {
    val copy = capabilityCopy(capability)
    ModalBottomSheet(onDismissRequest = onLater, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = InkTone.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(InkTone.accent.inkTint()), contentAlignment = Alignment.Center) {
                    Icon(copy.icon, null, tint = InkTone.accent, modifier = Modifier.size(24.dp))
                }
                Text("开启${capability.title}", style = MaterialTheme.typography.titleLarge, color = InkTone.text,
                    modifier = Modifier.semantics { heading() })
            }
            Text(copy.does, style = MaterialTheme.typography.bodyLarge, color = InkTone.text)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyLine("为什么需要", capability.explanation)
                CopyLine("不开启的话", copy.without)
            }
            Button(onClick = onOpen, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("去开启") }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("暂不", color = InkTone.soft) }
        }
    }
}

@Composable
private fun CopyLine(label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = InkText.caption, color = InkTone.soft, modifier = Modifier.width(64.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = InkTone.text, modifier = Modifier.weight(1f))
    }
}

/** 保存前复核：列出未授权的能力与各自「不开启的话」，可「仍然保存」或「去开启」第一项。 */
@Composable
fun CapabilitySaveCheckDialog(missing: Set<Capability>, onSaveAnyway: () -> Unit, onOpen: (Capability) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还有${missing.size}项没有授权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                missing.forEach { c ->
                    Column {
                        Text(c.title, style = MaterialTheme.typography.titleSmall, color = InkTone.text)
                        Text(capabilityCopy(c).without, style = MaterialTheme.typography.bodyMedium, color = InkTone.soft)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(missing.first()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("去开启") } },
        dismissButton = { TextButton(onClick = onSaveAnyway, modifier = Modifier.heightIn(min = 48.dp)) { Text("仍然保存") } },
    )
}
