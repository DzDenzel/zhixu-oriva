package com.planlock.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.record.BulkDeletion
import com.planlock.app.record.SyncConfirmation

/** Kept outside the filtered shelf so deleted and non-upcoming records remain reachable. */
@Composable
internal fun SyncConfirmationStatus(
    confirmations: List<SyncConfirmation>,
    bulkDeletion: BulkDeletion?,
    onSelect: (SyncConfirmation) -> Unit,
    onBulkSelect: (BulkDeletion) -> Unit,
) {
    val protectedDeletion = bulkDeletion?.takeUnless { it.approved }
    if (confirmations.isEmpty() && protectedDeletion == null) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (confirmations.isNotEmpty()) {
                Text("${confirmations.size} 条修改需要你确认", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .22f).dp)) {
                    items(confirmations, key = { it.key }) { confirmation ->
                        TextButton(onClick = { onSelect(confirmation) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("需要确认 · ${confirmation.presentation().title}", Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            protectedDeletion?.let { deletion ->
                TextButton(onClick = { onBulkSelect(deletion) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("有 ${deletion.count} 条记录在服务端消失", Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
internal fun SyncConfirmationDialog(
    confirmation: SyncConfirmation,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onResolve: (Boolean) -> Unit,
) {
    val presentation = confirmation.presentation()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认这项修改") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .42f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(presentation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(presentation.explanation, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("我的版本", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("对方版本", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                }
                presentation.fields.forEach { field ->
                    HorizontalDivider()
                    Text(field.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(field.mine, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(field.remote, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            // Vertical actions keep exact labels and 48dp hit areas at large font scales.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OperationFeedback(busy, error)
                Button(onClick = { onResolve(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(presentation.keepRemoteLabel)
                }
                OutlinedButton(onClick = { onResolve(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(presentation.keepMineLabel)
                }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("稍后处理")
                }
            }
        },
    )
}

@Composable
internal fun BulkDeletionDialog(
    deletion: BulkDeletion,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认清理本机记录") },
        text = {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .42f).dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("有 ${deletion.count} 条记录在服务端消失")
                Text("其中有 ${deletion.plans.size} 项计划、${deletion.schedules.size} 项日程。服务端恢复旧备份也可能造成记录消失。")
                Text("确认后将清理这些记录在本机的副本及提醒。若你不确定原因，请先取消并核对另一台设备或服务端备份。取消会保留本机记录。")
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OperationFeedback(busy, error)
                Button(onClick = onApprove, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认清理") }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
            }
        },
    )
}

@Composable
private fun OperationFeedback(busy: Boolean, error: String?) {
    if (busy || error != null) Text(
        if (busy) "正在处理，请稍候" else error.orEmpty(),
        color = if (error != null && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
