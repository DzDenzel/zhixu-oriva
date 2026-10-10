package com.planlock.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.planlock.app.api.*
import com.planlock.app.record.DayTimeline
import com.planlock.app.record.OccurrenceDate
import com.planlock.app.record.PlanOccurrence
import com.planlock.app.record.RecordStore
import com.planlock.app.record.ScheduleReminderScheduler
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.security.BackupPasswordStore
import com.planlock.app.sync.AppSync
import com.planlock.design.PageAtmosphere
import com.planlock.design.V2Tokens
import com.planlock.design.planLockAtmosphere
import com.planlock.feature.plan.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenPage: (AssistantDestination) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val sync = remember { AppSync.get(context) }
    val reminders = remember { ScheduleReminderScheduler(context) }
    val gate = remember {
        PlanActionGate(store, ApiClient.create(), BackupPasswordStore(context.planLockPreferences),
            context.planLockPreferences, sync = sync)
    }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var plans by remember { mutableStateOf<List<PlanSnapshotEntity>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf(AssistantReply("可以问今日安排、连续达成、分心报告，或记一件事、开始 / 结束计划。")) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<AssistantScheduleDraft?>(null) }
    var confirming by remember { mutableStateOf<AssistantAction.ChangePlan?>(null) }
    var pendingAction by remember { mutableStateOf<PendingPlanAction?>(null) }

    PlanActionDialogHost(gate, pendingAction) { pendingAction = null }

    // Queries read Room and the existing per-user report cache only, without a refresh request.
    suspend fun snapshot(): AssistantSnapshot {
        plans = store.plans()
        return loadAssistantSnapshot(store, sync)
    }

    fun submit() {
        if (busy || input.isBlank()) return
        busy = true
        scope.launch {
            try {
                reply = AssistantCapabilityRegistry.respond(input, snapshot())
                listState.animateScrollToItem(0)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reply = AssistantReply("本机数据读取失败，请稍后重试。") }
            finally { busy = false }
        }
    }

    fun runAction(action: AssistantAction) {
        when (action) {
            is AssistantAction.OpenPage -> onOpenPage(action.destination)
            is AssistantAction.EditSchedule -> {
                scope.launch { plans = store.plans(); draft = action.draft }
            }
            is AssistantAction.ChangePlan -> confirming = action
        }
    }

    draft?.let { value ->
        ScheduleEditorSheet(
            current = null, plans = plans, draft = value, saving = saving,
            onDismiss = { draft = null }, onDelete = null,
            onSave = { request ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            saveSchedule(store, reminders, sync, request)
                            draft = null
                            reply = AssistantReply("已保存日程：${request.title} · ${request.startDate}。",
                                listOf(AssistantAction.OpenPage("查看日程与计划", AssistantDestination.PLANS)))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { reply = AssistantReply("日程保存未完成，请检查后重试。") }
                        finally { saving = false }
                    }
                }
            },
        )
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = { Text("确认${if (action.enabled) "开始" else "结束"}计划") },
            text = { Text("${action.label}？${if (action.enabled) "将恢复当前时段的执行。" else "将暂停该计划；保护开启时还需验证备用密码。"}") },
            dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = { confirming = null }) { Text("取消") } },
            confirmButton = {
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            // Re-read at confirmation so stale/repeated requests cannot invert the desired state.
                            val target = store.plan(action.planId)
                            val now = Calendar.getInstance()
                            val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                            if (target == null || !PlanOccurrence.isWithinOccurrence(target, minutes, OccurrenceDate.from(now))) {
                                reply = AssistantReply("计划已不在当前执行时段，请重新选择。")
                            } else if ((target.status == 1) == action.enabled) {
                                reply = AssistantReply("计划已处于目标状态，无需重复操作。")
                            } else {
                                val completed: (String?) -> Unit = { warning ->
                                    reply = AssistantReply("已${if (action.enabled) "开始" else "结束"} ${target.name}。${warning.orEmpty()}")
                                }
                                val requirement = gate.requirement(target, ProtectedPlanAction.PAUSE)
                                if (requirement == PlanActionRequirement.FREE) {
                                    when (val result = gate.perform(target, ProtectedPlanAction.PAUSE, null)) {
                                        is PlanActionResult.Completed -> completed(result.warning)
                                        is PlanActionResult.Failed -> reply = AssistantReply(result.message)
                                        else -> reply = AssistantReply("计划保护状态已变化，请重新确认。")
                                    }
                                } else pendingAction = PendingPlanAction(target, ProtectedPlanAction.PAUSE,
                                    requirement == PlanActionRequirement.SETUP, onCompleted = completed)
                            }
                            confirming = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { reply = AssistantReply("计划操作未完成，请重试。") }
                        finally { busy = false }
                    }
                }) { Text("确认") }
            },
        )
    }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.ASSISTANT),
        containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp), state = listState,
            contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 2.dp)) {
                    Text("本地助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("本地规则，非 AI 对话 · 查询不联网", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, label = { Text("输入本地指令") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(18.dp))
                    Button(onClick = ::submit, enabled = !busy && input.isNotBlank()) { Text(if (busy) "处理中" else "发送") }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp), color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.background(Brush.linearGradient(listOf(V2Tokens.brandBright, V2Tokens.brandDeep))).padding(22.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("写入前由你确认，保存后同步。", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodyMedium)
                            Text(reply.text, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            reply.actions.forEach { action ->
                                OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !saving, onClick = { runAction(action) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(action.label) }
                            }
                        }
                    }
                }
            }
            item { Text("试试这些指令", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(AssistantCapabilityRegistry.entries, key = { it.id }) { capability ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        input = capability.example
                        scope.launch { listState.animateScrollToItem(0) }
                    }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("✦", Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(capability.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(capability.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Text(capability.example, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
