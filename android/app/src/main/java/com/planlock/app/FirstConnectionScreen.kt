package com.planlock.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.planLockBackdrop

/** The choice is committed before process-owned synchronization is admitted. */
@Composable
internal fun FirstConnectionScreen(busy: Boolean, error: String?, onLocal: () -> Unit, onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().planLockBackdrop().windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(36.dp))
        Text("从这里开始", style = MaterialTheme.typography.headlineLarge, color = InkTone.text)
        Text("先安排好今天。日程、计划和执行记录，都可以保存在这台设备上。",
            style = MaterialTheme.typography.bodyLarge, color = InkTone.soft)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLocal, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (busy) "正在保存…" else "仅本地使用")
        }
        Text("无需账号。以后可在设置中连接自己的后端。", style = MaterialTheme.typography.bodyMedium, color = InkTone.soft)
        HorizontalDivider(color = InkTone.hairline)
        OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("连接我的后端")
        }
        Text("已有部署配置？扫码或粘贴配置，连接后使用账号与同步。", style = MaterialTheme.typography.bodyMedium, color = InkTone.soft)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
