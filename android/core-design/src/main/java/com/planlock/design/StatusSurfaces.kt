package com.planlock.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared presentation only: callers retain state, actions and persistence. */
@Composable
fun StatusCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}

/** Intrinsic button widths make the pair wrap before labels or touch targets are clipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusActionRow(
    primaryLabel: String?,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    FlowRow(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2) {
        primaryLabel?.let {
            Button(onClick = onPrimary, modifier = Modifier.widthIn(min = 128.dp).heightIn(min = 48.dp)) { Text(it) }
        }
        secondaryLabel?.let {
            OutlinedButton(onClick = onSecondary, modifier = Modifier.widthIn(min = 128.dp).heightIn(min = 48.dp)) { Text(it) }
        }
    }
}

/** Reserve the same header geometry when a continuing status appears or disappears. */
@Composable
fun StatusRegion(content: @Composable () -> Unit) {
    Box(Modifier.width(128.dp).height(48.dp), contentAlignment = Alignment.CenterEnd) { content() }
}

@Composable
internal fun StatusDetailsDialog(title: String, detail: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
        })
}

/** Failure stays a page state; raw transport diagnostics are only revealed on request. */
@Composable
fun RepositoryFailurePanel(detail: String, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    StatusCard(
        title = "暂时无法读取插件仓库",
        description = "当前无法获取插件列表，请稍后重试。",
        leading = { Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp)) },
    ) {
        StatusActionRow("重试", onRetry, "诊断详情", { expanded = true })
    }
    if (expanded) StatusDetailsDialog("诊断详情", detail) { expanded = false }
}
