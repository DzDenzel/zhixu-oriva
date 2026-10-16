package com.planlock.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
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
import com.planlock.design.Ink
import com.planlock.design.InkPulseDot
import com.planlock.design.InkMasthead
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.planlock.design.InkHue
import com.planlock.design.color
import com.planlock.design.inkTint
import com.planlock.design.InkRule
import com.planlock.design.InkSeal
import com.planlock.design.AssistantMark
import com.planlock.design.AssistantIdentity
import androidx.compose.runtime.saveable.rememberSaveable
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.LocalDarkTheme
import com.planlock.design.inkReveal
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.planlock.design.V2Tokens
import com.planlock.design.planLockAtmosphere
import com.planlock.feature.plan.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(onOpenPage: (AssistantDestination) -> Unit = {}, onBack: () -> Unit = {}) {
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
    // 会话线程：v2.x 本地规则逐条作答；v3.0 接入 AI 后同一线程承载多轮对话、草稿卡与档案引用。
    val thread = remember {
        mutableStateListOf<AssistantTurn>()
    }
    var mode by rememberSaveable { mutableStateOf(AssistantMode.LOCAL) }
    val connection by ConnectionStore.state.collectAsState()
    // AI 组件随 v3.0 内核提供；在那之前 AI 模式一律如实回落到本地规则（PRD §5.2 A5）。
    val aiReady = false
    fun post(reply: AssistantReply, question: String? = null, attachments: List<AssistantAttachment> = emptyList()) {
        thread.add(AssistantTurn(thread.size.toLong(), question, reply, attachments))
    }
    // 待发附件（CHANGE-054）：只在本次对话的内存里，不落库、不上传。
    val pending = remember { mutableStateListOf<AssistantAttachment>() }
    fun attach(uris: List<android.net.Uri>) {
        uris.forEach { uri ->
            if (pending.size < AssistantAttachmentRules.MAX_COUNT && pending.none { it.uri == uri }) pending.add(context.describeAttachment(uri))
        }
    }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(AssistantAttachmentRules.MAX_COUNT),
    ) { uris -> attach(uris) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> attach(uris) }
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
        if (busy || (input.isBlank() && pending.isEmpty())) return
        busy = true
        val question = input.trim().ifEmpty { null }
        val sent = pending.toList()
        input = ""
        pending.clear()
        scope.launch {
            try {
                val answer = question?.let { AssistantCapabilityRegistry.respond(it, snapshot()) }
                val note = if (sent.isEmpty()) null else attachmentNote(sent, backendConnected = connection.config != null)
                post(
                    when {
                        answer == null -> AssistantReply(note.orEmpty())
                        note == null -> answer
                        else -> answer.copy(text = answer.text + "\n\n" + note)
                    },
                    question, sent,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { post(AssistantReply("本机数据读取失败，请稍后重试。"), question, sent) }
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
                            post(AssistantReply("已保存日程：${request.title} · ${request.startDate}。",
                                listOf(AssistantAction.OpenPage("查看日程与计划", AssistantDestination.PLANS))))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { post(AssistantReply("日程保存未完成，请检查后重试。")) }
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
                                post(AssistantReply("计划已不在当前执行时段，请重新选择。"))
                            } else if ((target.status == 1) == action.enabled) {
                                post(AssistantReply("计划已处于目标状态，无需重复操作。"))
                            } else {
                                val completed: (String?) -> Unit = { warning ->
                                    post(AssistantReply("已${if (action.enabled) "开始" else "结束"} ${target.name}。${warning.orEmpty()}"))
                                }
                                val requirement = gate.requirement(target, ProtectedPlanAction.PAUSE)
                                if (requirement == PlanActionRequirement.FREE) {
                                    when (val result = gate.perform(target, ProtectedPlanAction.PAUSE, null)) {
                                        is PlanActionResult.Completed -> completed(result.warning)
                                        is PlanActionResult.Failed -> post(AssistantReply(result.message))
                                        else -> post(AssistantReply("计划保护状态已变化，请重新确认。"))
                                    }
                                } else pendingAction = PendingPlanAction(target, ProtectedPlanAction.PAUSE,
                                    requirement == PlanActionRequirement.SETUP, onCompleted = completed)
                            }
                            confirming = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { post(AssistantReply("计划操作未完成，请重试。")) }
                        finally { busy = false }
                    }
                }) { Text("确认") }
            },
        )
    }

    LaunchedEffect(thread.size) { if (thread.isNotEmpty()) listState.animateScrollToItem(thread.size - 1) }

    Scaffold(
        modifier = Modifier.planLockAtmosphere(PageAtmosphere.ASSISTANT),
        containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // 页头固定在顶部：对话再长、输入法再高，返回与模式开关都在手边
        topBar = {
            Box(Modifier.padding(horizontal = 12.dp)) {
                // 输入法弹出 = 全屏（导航收起）时才出现返回；平时由底部导航离开
                AssistantMasthead(mode, aiReady, showBack = WindowInsets.isImeVisible, onBack = onBack) { target ->
                    if (target == mode) return@AssistantMasthead
                    mode = target
                    if (target == AssistantMode.AI && !aiReady) post(AssistantReply(
                        if (connection.config == null) "AI 模式需要先连接你自己的个人后端。连接之前，我先用本地规则回答，查询不联网。"
                        else "已连接你的个人后端，但 AI 组件还没有启用。启用之前，我先用本地规则回答。"
                    ))
                    if (target == AssistantMode.LOCAL) post(AssistantReply("已切回本地模式：本地规则作答，查询不联网。"))
                }
            }
        },
        bottomBar = {
            AssistantComposer(
                value = input, onValueChange = { input = it }, busy = busy,
                onSend = ::submit,
                attachments = pending,
                onRemoveAttachment = { pending.remove(it) },
                onPickImages = {
                    pickImages.launch(androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickFiles = { pickFiles.launch(AssistantAttachmentRules.documentTypes) },
                suggestions = if (thread.isEmpty()) AssistantCapabilityRegistry.entries.map { it.example } else emptyList(),
                onSuggestion = { input = it },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), state = listState,
            contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(thread, key = { it.id }) { turn ->
                Column(Modifier.fillMaxWidth().inkReveal(0, distance = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (turn.attachments.isNotEmpty()) SentAttachments(turn.attachments)
                    turn.question?.let { UserBubble(it) }
                    AssistantLetter(turn.reply, enabled = !busy && !saving, onAction = ::runAction)
                }
            }
            if (busy) item { ThinkingRow() }
        }
    }
}

/** 一轮对话：用户一句（可空，系统主动回复时为空）+ 助理一封回函。 */
internal data class AssistantTurn(
    val id: Long,
    val question: String?,
    val reply: AssistantReply,
    val attachments: List<AssistantAttachment> = emptyList(),
)

/**
 * 当前作答模式。PRD §5.2 A5 要求「明确告知当前是哪种模式」：
 * 未连后端或未启用 AI = 本地规则；v3.0 起连接个人后端且启用 AI 时切到 AI 模式（同一界面，徽记换色）。
 */
internal enum class AssistantMode(val label: String, val note: String) {
    LOCAL("本地", "本地规则作答 · 查询不联网"),
    AI("AI", "经你的个人后端作答"),
}

@Composable
private fun AssistantMasthead(mode: AssistantMode, aiReady: Boolean, showBack: Boolean, onBack: () -> Unit, onModeChange: (AssistantMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).inkReveal(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 返回：仅在输入法弹出、底部导航收起时出现（这时它是唯一的回路），横向展开淡入
            AnimatedVisibility(
                visible = showBack,
                enter = fadeIn(tween(180)) + expandHorizontally(tween(220, easing = Ink.Easing)),
                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180, easing = Ink.Easing)),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp).size(40.dp)) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = InkTone.soft)
                }
            }
            AssistantMark(markSize = 44.dp, active = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(AssistantIdentity.NAME, style = InkText.masthead.copy(fontSize = 26.sp, lineHeight = 32.sp), color = InkTone.text)
                // 全屏输入态（返回箭头出现）收起理念句，窄屏下不再被挤断
                AnimatedVisibility(visible = !showBack) {
                    com.planlock.design.FitText(AssistantIdentity.TAGLINE, InkText.meta.copy(fontSize = 11.sp), InkTone.faint)
                }
            }
            ModeSwitch(mode, onModeChange)
        }
        Text(
            when {
                mode == AssistantMode.LOCAL -> mode.note
                aiReady -> mode.note
                else -> "AI 未就绪 · 暂由本地规则回答"
            },
            style = InkText.meta.copy(fontSize = 11.sp),
            color = if (mode == AssistantMode.AI && !aiReady) InkHue.amber.color() else InkTone.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 模式开关：本地 / AI 两格，选中格用该模式的颜色（本地 = 草绿，AI = 朱）。 */
@Composable
private fun ModeSwitch(mode: AssistantMode, onModeChange: (AssistantMode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(InkTone.sunk).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantMode.entries.forEach { option ->
            val selected = option == mode
            val hue = if (option == AssistantMode.AI) InkHue.seal.color() else InkHue.sport.color()
            val bg by androidx.compose.animation.animateColorAsState(if (selected) hue else androidx.compose.ui.graphics.Color.Transparent, label = "mode-bg")
            Text(option.label, style = MaterialTheme.typography.labelMedium,
                color = if (selected) com.planlock.design.InkTone.onAccent else InkTone.soft,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
                    .clickable { onModeChange(option) }
                    .semantics { contentDescription = option.label + "模式" + if (selected) "，已选中" else "" }
                    .padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(InkTone.inverse)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = InkTone.onInverse)
        }
    }
}

/** 回函：左侧小朱印 + 纸色气泡（衬线正文）；写入类动作标「草稿」。 */
@Composable
private fun AssistantLetter(reply: AssistantReply, enabled: Boolean, onAction: (AssistantAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AssistantMark(markSize = 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f, fill = false)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(InkTone.raised)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(reply.text, color = InkTone.text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InkTone.display, lineHeight = 26.sp))
            reply.actions.forEach { action ->
                val isDraft = action is AssistantAction.EditSchedule || action is AssistantAction.ChangePlan
                TextButton(onClick = { onAction(action) }, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text((if (isDraft) "草稿 · " else "") + action.label + " →", color = InkTone.vermilion, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistantMark(markSize = 26.dp, active = true)
        Spacer(Modifier.width(10.dp))
        InkPulseDot(InkTone.vermilion, dotSize = 6.dp)
        Text(" 正在查阅本机记录…", style = InkText.meta, color = InkTone.soft)
    }
}

/** 输入台：建议短句横排 + 输入框 + 朱色发送印。 */
@Composable
private fun AssistantComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    attachments: List<AssistantAttachment> = emptyList(),
    onRemoveAttachment: (AssistantAttachment) -> Unit = {},
    onPickImages: () -> Unit = {},
    onPickFiles: () -> Unit = {},
) {
    val canSend = !busy && (value.isNotBlank() || attachments.isNotEmpty())
    Column(
        Modifier.fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (attachments.isNotEmpty()) AttachmentTray(attachments, onRemoveAttachment)
        if (suggestions.isNotEmpty() && attachments.isEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(suggestions) { index, text ->
                    val hue = listOf(InkHue.study, InkHue.work, InkHue.sport, InkHue.sleep, InkHue.rose, InkHue.amber)[index % 6].color()
                    Surface(onClick = { onSuggestion(text) }, shape = RoundedCornerShape(999.dp), color = hue.inkTint()) {
                        Text(text, style = MaterialTheme.typography.labelMedium, color = hue, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AttachButton(enabled = !busy && attachments.size < AssistantAttachmentRules.MAX_COUNT, onPickImages = onPickImages, onPickFiles = onPickFiles)
            OutlinedTextField(
                value, onValueChange, placeholder = { Text("对${AssistantIdentity.NAME}说…", color = InkTone.faint) }, modifier = Modifier.weight(1f), singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = InkTone.hairline, focusedBorderColor = InkTone.vermilion,
                    unfocusedContainerColor = InkTone.raised, focusedContainerColor = InkTone.raised,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
            )
            Surface(
                onClick = onSend, enabled = canSend,
                shape = RoundedCornerShape(28.dp),
                color = if (canSend) InkTone.vermilion else InkTone.sunk,
                modifier = Modifier.size(52.dp).semantics { contentDescription = if (busy) "处理中" else "发送" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (busy) "…" else "发", fontFamily = InkTone.display, fontWeight = FontWeight.Black, fontSize = 22.sp,
                        color = if (canSend) InkTone.onAccent else InkTone.faint)
                }
            }
        }
    }
}
