package com.planlock.feature.plan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.api.JournalExportItem
import com.planlock.app.api.JournalRules
import com.planlock.app.api.TokenStore
import com.planlock.app.record.RecordStore
import com.planlock.app.record.entity.JournalSnapshotEntity
import com.planlock.app.sync.AppSync
import com.planlock.design.FormIssue
import com.planlock.design.InkFab
import com.planlock.design.InkFullScreenPage
import com.planlock.design.InkHue
import com.planlock.design.InkSaveBar
import com.planlock.design.InkSeal
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.PickerField
import com.planlock.design.PlanDatePicker
import com.planlock.design.SyncRefreshHost
import com.planlock.design.UIStatePanel
import com.planlock.design.UiState
import com.planlock.design.color
import com.planlock.design.inkFormField
import com.planlock.design.inkPressClick
import com.planlock.design.inkPressScale
import com.planlock.design.inkReveal
import com.planlock.design.inkTint
import com.planlock.design.rememberInkFormTargets
import com.planlock.design.rememberInkPress
import com.planlock.design.saveBarHint
import com.planlock.design.tint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun filterJournalEntries(
    entries: List<JournalSnapshotEntity>,
    mood: String? = null,
    tag: String? = null,
    query: String = "",
): List<JournalSnapshotEntity> {
    val needle = query.trim().lowercase()
    return entries
        .filter { it.status == 1 }
        .filter { mood == null || it.mood == mood }
        .filter { row ->
            val tags = JournalRules.parseTagsJson(row.tagsJson)
            (tag == null || tag in tags) &&
                (needle.isEmpty() ||
                    row.content.lowercase().contains(needle) ||
                    row.mood.orEmpty().lowercase().contains(needle) ||
                    row.entryDate.lowercase().contains(needle) ||
                    tags.any { it.lowercase().contains(needle) })
        }
        .sortedWith(compareByDescending<JournalSnapshotEntity> { it.entryDate }.thenByDescending { it.id })
}

internal fun distinctJournalTags(entries: List<JournalSnapshotEntity>): List<String> =
    entries.asSequence()
        .filter { it.status == 1 }
        .flatMap { JournalRules.parseTagsJson(it.tagsJson).asSequence() }
        .distinct()
        .toList()

internal fun journalFormIssues(entryDate: String, mood: String?, tags: List<String>, content: String): List<FormIssue> {
    val issues = mutableListOf<FormIssue>()
    if (!JournalRules.isValidIsoDate(entryDate)) {
        issues += FormIssue("date", "请选择有效日期")
    }
    if (content.isBlank()) {
        issues += FormIssue("content", "请填写日常记录内容")
    } else if (runCatching { JournalRules.normalizeContent(content) }.isFailure) {
        issues += FormIssue("content", "正文长度不能超过 10000 字且不能含控制字符")
    }
    if (mood != null && runCatching { JournalRules.normalizeMood(mood) }.isFailure) {
        issues += FormIssue("mood", "心情长度不能超过 24 字且不能含控制字符")
    }
    if (runCatching { JournalRules.normalizeTags(tags) }.isFailure) {
        issues += FormIssue("tags", "标签最多 8 个，每个不超过 20 字")
    }
    return issues
}

@Composable
internal fun moodToneColor(mood: String?): Color = when (mood) {
    "平静" -> InkTone.jade
    "开心", "充实" -> InkTone.vermilion
    "专注" -> InkHue.study.color()
    "疲惫" -> InkTone.brass
    "焦虑" -> InkTone.indigo
    else -> InkTone.soft
}

private fun copyMarkdownToClipboard(context: Context, markdown: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("知序日常记录", markdown))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    openComposerInitially: Boolean = false,
    onNotice: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { RecordStore(context) }
    val sync = remember { AppSync.get(context) }
    val syncState by sync.coordinator.state.collectAsState()
    val authState by TokenStore.state.collectAsState()
    val localRevision by store.localRevision.collectAsState()
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<JournalSnapshotEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }

    var selectedMood by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    var showEditor by rememberSaveable { mutableStateOf(openComposerInitially) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(authState.dataScope, localRevision, syncState.revision, reloadNonce) {
        try {
            val loaded = withContext(Dispatchers.IO) { store.journals() }
            entries = loaded
            loadError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = "日常记录读取失败，请重试"
        } finally {
            loading = false
        }
    }

    val filtered = remember(entries, selectedMood, selectedTag, query) {
        filterJournalEntries(entries, selectedMood, selectedTag, query)
    }
    val allTags = remember(entries) { distinctJournalTags(entries) }
    val moodOptions = remember(entries) {
        (JournalRules.PRESET_MOODS + entries.mapNotNull { it.mood?.trim()?.takeIf { m -> m.isNotEmpty() } }).distinct()
    }
    val editingEntry = remember(entries, editingId) {
        editingId?.let { id -> entries.firstOrNull { it.id == id } }
    }

    if (showEditor) {
        JournalEditorSheet(
            current = editingEntry,
            suggestedTags = allTags,
            saving = saving,
            onDismiss = { if (!saving) { showEditor = false; editingId = null } },
            onCopySingle = { item ->
                val md = JournalRules.exportMarkdown(
                    listOf(
                        JournalExportItem(
                            item.id,
                            item.entryDate,
                            item.mood,
                            JournalRules.parseTagsJson(item.tagsJson),
                            item.content,
                        )
                    ),
                    scopeLabel = if (authState.dataScope == "local\nlocal") "本地游客模式" else "个人空间",
                )
                copyMarkdownToClipboard(context, md)
                onNotice("已复制这条日常记录为 Markdown")
            },
            onSave = { entryDate, mood, tags, content ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            val expectedScope = authState.dataScope
                            withContext(Dispatchers.IO) {
                                sync.withLocalMutation {
                                    store.saveJournalChange(
                                        entryDate = entryDate,
                                        mood = mood,
                                        tags = tags,
                                        content = content,
                                        id = editingEntry?.id,
                                        expectedScope = expectedScope,
                                    )
                                }
                            }
                            sync.localChangesCommitted()
                            showEditor = false
                            editingId = null
                            onNotice("已保存日常记录")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            onNotice(e.message ?: "保存失败，请重试")
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            onDelete = editingEntry?.let { target ->
                {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    sync.withLocalMutation {
                                        store.softDeleteJournal(target.id, target.scope)
                                    }
                                }
                                sync.localChangesCommitted()
                                showEditor = false
                                editingId = null
                                onNotice("已删除日常记录")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                onNotice(e.message ?: "删除失败，请重试")
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("日常记录", style = MaterialTheme.typography.titleLarge, color = InkTone.text)
                        Text(
                            if (entries.isEmpty()) "随手记下心情与片段" else "共 ${entries.size} 条",
                            style = InkText.caption,
                            color = InkTone.soft,
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            val items = filtered.map {
                                JournalExportItem(
                                    id = it.id,
                                    entryDate = it.entryDate,
                                    mood = it.mood,
                                    tags = JournalRules.parseTagsJson(it.tagsJson),
                                    content = it.content,
                                )
                            }
                            val scopeLabel = if (authState.dataScope == "local\nlocal") "本地游客模式" else "个人空间"
                            copyMarkdownToClipboard(context, JournalRules.exportMarkdown(items, scopeLabel = scopeLabel))
                            onNotice("已复制 ${items.size} 条记录为 Markdown")
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!loading) {
                InkFab(onClick = { editingId = null; showEditor = true }, description = "写一条日常记录")
            }
        },
    ) { innerPadding ->
        SyncRefreshHost(
            running = syncState.running,
            error = loadError ?: syncState.error,
            onRefresh = { reloadNonce++; sync.coordinator.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            loginRequired = syncState.loginRequired,
            errorEventKey = "${TokenStore.userId}:${syncState.attempts}" + loadError?.let { ":journal:$it" }.orEmpty(),
        ) { _ ->
            when {
                loading -> UIStatePanel(UiState.LOADING)
                loadError != null && entries.isEmpty() -> UIStatePanel(
                    state = UiState.ERROR,
                    message = loadError,
                    onAction = { reloadNonce++ },
                )
                else -> {
                    val chipColors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = InkTone.jade.inkTint(),
                        selectedLabelColor = InkTone.jade,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "filters") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = { Text("搜索内容、标签或日期…") },
                                    singleLine = true,
                                    trailingIcon = if (query.isNotEmpty()) {
                                        {
                                            IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                                                Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                            }
                                        }
                                    } else null,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "mood-all") {
                                        FilterChip(
                                            selected = selectedMood == null,
                                            onClick = { selectedMood = null },
                                            label = { Text("全部") },
                                            colors = chipColors,
                                            modifier = Modifier.heightIn(min = 40.dp),
                                        )
                                    }
                                    items(moodOptions, key = { "mood-$it" }) { mood ->
                                        FilterChip(
                                            selected = selectedMood == mood,
                                            onClick = { selectedMood = if (selectedMood == mood) null else mood },
                                            label = { Text(mood) },
                                            colors = chipColors,
                                            modifier = Modifier.heightIn(min = 40.dp),
                                        )
                                    }
                                }
                                if (allTags.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(allTags, key = { "tag-$it" }) { tag ->
                                            FilterChip(
                                                selected = selectedTag == tag,
                                                onClick = { selectedTag = if (selectedTag == tag) null else tag },
                                                label = { Text("#$tag") },
                                                colors = chipColors,
                                                modifier = Modifier.heightIn(min = 38.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (filtered.isEmpty()) {
                            item(key = "empty") {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    InkSeal("随", sealSize = 48.dp, color = InkTone.jade)
                                    Text(
                                        if (entries.isEmpty()) "今天想记点什么？" else "没有符合条件的记录",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = InkTone.text,
                                    )
                                    Text(
                                        if (entries.isEmpty()) "写下的心情与片段只属于你，可随时导出为 Markdown。" else "试试调整心情、标签或搜索关键词。",
                                        style = InkText.caption,
                                        color = InkTone.soft,
                                    )
                                    if (entries.isEmpty()) {
                                        OutlinedButton(
                                            onClick = { editingId = null; showEditor = true },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("记一笔")
                                        }
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(filtered, key = { _, item -> "journal-${item.id}" }) { index, item ->
                                JournalEntryCard(
                                    entry = item,
                                    modifier = Modifier.inkReveal(index.coerceAtMost(8)),
                                    onClick = {
                                        editingId = item.id
                                        showEditor = true
                                    },
                                    onCopy = {
                                        val md = JournalRules.exportMarkdown(
                                            listOf(
                                                JournalExportItem(
                                                    item.id,
                                                    item.entryDate,
                                                    item.mood,
                                                    JournalRules.parseTagsJson(item.tagsJson),
                                                    item.content,
                                                )
                                            ),
                                            scopeLabel = if (authState.dataScope == "local\nlocal") "本地游客模式" else "个人空间",
                                        )
                                        copyMarkdownToClipboard(context, md)
                                        onNotice("已复制这条记录为 Markdown")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalEntryCard(
    entry: JournalSnapshotEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    val tags = remember(entry.tagsJson) { JournalRules.parseTagsJson(entry.tagsJson) }
    val moodColor = moodToneColor(entry.mood)
    val press = rememberInkPress()
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .inkPressScale(press)
            .clip(shape)
            .inkPressClick(press, onClick = onClick),
        shape = shape,
        color = InkTone.raised,
        border = BorderStroke(1.dp, InkTone.hairline.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.entryDate, style = InkText.caption.copy(fontWeight = FontWeight.SemiBold), color = InkTone.soft)
                entry.mood?.takeIf { it.isNotBlank() }?.let { mood ->
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(moodColor.inkTint())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(mood, style = InkText.caption.copy(fontWeight = FontWeight.SemiBold), color = moodColor, maxLines = 1)
                    }
                }
                if (entry.id < 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("待同步", style = InkText.caption, color = InkTone.faint)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 Markdown", tint = InkTone.soft, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyMedium,
                color = InkTone.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (tags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { tag ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(InkTone.sunk)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("#$tag", style = InkText.caption, color = InkTone.soft, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalEditorSheet(
    current: JournalSnapshotEntity?,
    suggestedTags: List<String> = emptyList(),
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onCopySingle: ((JournalSnapshotEntity) -> Unit)? = null,
    onSave: (entryDate: String, mood: String?, tags: List<String>, content: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var entryDate by remember(current?.id) { mutableStateOf(current?.entryDate ?: today) }
    var mood by remember(current?.id) { mutableStateOf(current?.mood) }
    var tags by remember(current?.id) { mutableStateOf(JournalRules.parseTagsJson(current?.tagsJson)) }
    var tagInput by remember(current?.id) { mutableStateOf("") }
    var tagError by remember(current?.id) { mutableStateOf<String?>(null) }
    var content by remember(current?.id) { mutableStateOf(current?.content.orEmpty()) }

    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember(current?.id) { mutableStateOf(false) }
    var attempted by remember(current?.id) { mutableStateOf(false) }

    val issues = journalFormIssues(entryDate, mood, tags, content)
    val shownIssues = if (attempted) issues else emptyList()
    val targets = rememberInkFormTargets()
    val scope = rememberCoroutineScope()
    val revealFirst: () -> Unit = { issues.firstOrNull()?.let { scope.launch { targets.reveal(it.field) } } }

    if (showDatePicker) {
        PlanDatePicker(
            title = "记录日期",
            initialIsoDate = entryDate,
            onConfirm = { entryDate = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }

    InkFullScreenPage(onDismissRequest = { if (!saving) onDismiss() }) {
        if (confirmDelete && onDelete != null) {
            AlertDialog(
                onDismissRequest = { if (!saving) confirmDelete = false },
                title = { Text("删除这条日常记录？") },
                text = { Text("删除后将从列表移除并同步到云端，无法撤销。") },
                confirmButton = {
                    TextButton(
                        enabled = !saving,
                        onClick = { confirmDelete = false; onDelete() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !saving,
                        onClick = { confirmDelete = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("取消")
                    }
                },
            )
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { if (!saving) onDismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(if (current == null) "写一条" else "编辑日常") },
                actions = {
                    if (current != null && onCopySingle != null) {
                        IconButton(onClick = { onCopySingle(current) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 Markdown")
                        }
                    }
                    if (current != null && onDelete != null) {
                        IconButton(
                            enabled = !saving,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除记录", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PickerField(
                    title = "日期",
                    value = entryDate,
                    modifier = Modifier.inkFormField(targets, "date"),
                    onClick = { showDatePicker = true },
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("心情（可选）", style = InkText.eyebrow, color = InkTone.soft)
                    val allMoodChoices = remember(current?.mood) {
                        (JournalRules.PRESET_MOODS + listOfNotNull(current?.mood?.takeIf { it.isNotBlank() })).distinct()
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().inkFormField(targets, "mood"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        allMoodChoices.forEach { option ->
                            val selected = mood == option
                            val tone = moodToneColor(option)
                            FilterChip(
                                selected = selected,
                                onClick = { mood = if (selected) null else option },
                                label = { Text(option) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = tone.inkTint(),
                                    selectedLabelColor = tone,
                                ),
                                modifier = Modifier.heightIn(min = 44.dp),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().inkFormField(targets, "tags"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("标签（可选）", style = InkText.eyebrow, color = InkTone.soft, modifier = Modifier.weight(1f))
                        Text("${tags.size} / ${JournalRules.MAX_TAGS_COUNT}", style = InkText.caption, color = InkTone.soft)
                    }
                    if (tags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = InkTone.raised,
                                    border = BorderStroke(1.dp, InkTone.hairline),
                                    modifier = Modifier.clickable {
                                        tags = tags.filterNot { it == tag }
                                        tagError = null
                                    },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text("#$tag", style = InkText.caption, color = InkTone.text)
                                        Icon(Icons.Filled.Close, contentDescription = "移除标签 $tag", modifier = Modifier.size(14.dp), tint = InkTone.soft)
                                    }
                                }
                            }
                        }
                    }
                    fun appendTag(raw: String) {
                        val candidate = raw.trim()
                        if (candidate.isEmpty()) return
                        val next = tags + candidate
                        val check = runCatching { JournalRules.normalizeTags(next) }
                        if (check.isSuccess) {
                            tags = check.getOrThrow()
                            tagInput = ""
                            tagError = null
                        } else {
                            tagError = check.exceptionOrNull()?.message ?: "标签无效"
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { value ->
                                tagError = null
                                if (value.contains(',') || value.contains('，') || value.contains('\n')) {
                                    value.split(',', '，', '\n').forEach { appendTag(it) }
                                } else {
                                    tagInput = value
                                }
                            },
                            placeholder = { Text("输入标签，最多 20 字") },
                            singleLine = true,
                            isError = tagError != null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { appendTag(tagInput) },
                            enabled = tagInput.isNotBlank() && tags.size < JournalRules.MAX_TAGS_COUNT,
                            modifier = Modifier.heightIn(min = 52.dp),
                        ) {
                            Text("添加")
                        }
                    }
                    tagError?.let { msg ->
                        Text(msg, style = InkText.caption, color = MaterialTheme.colorScheme.error)
                    }
                    val unusedSuggestions = remember(suggestedTags, tags) {
                        suggestedTags.filterNot { it in tags }.take(6)
                    }
                    if (unusedSuggestions.isNotEmpty() && tags.size < JournalRules.MAX_TAGS_COUNT) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(unusedSuggestions, key = { "suggest-$it" }) { tag ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(InkTone.sunk)
                                        .clickable { appendTag(tag) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text("+ #$tag", style = InkText.caption, color = InkTone.soft)
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("正文", style = InkText.eyebrow, color = InkTone.soft, modifier = Modifier.weight(1f))
                        Text("${content.length} / ${JournalRules.MAX_CONTENT_LENGTH}", style = InkText.caption, color = InkTone.soft)
                    }
                    val contentIssue = shownIssues.firstOrNull { it.field == "content" }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        placeholder = { Text("记下今天发生的事、感受或一点想法…") },
                        isError = contentIssue != null,
                        supportingText = contentIssue?.let { issue -> { Text(issue.message) } },
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .inkFormField(targets, "content", focusable = true),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            InkSaveBar(
                label = "保存记录",
                busy = saving,
                hint = saveBarHint(shownIssues),
                onHint = revealFirst,
                onClick = {
                    attempted = true
                    if (issues.isNotEmpty()) {
                        revealFirst()
                    } else {
                        onSave(entryDate, mood, tags, content)
                    }
                },
            )
        }
    }
}

@Composable
internal fun TodayJournalEntryCard(
    journals: List<JournalSnapshotEntity>,
    selectedDate: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember(journals) { journals.filter { it.status == 1 } }
    val dayEntries = remember(active, selectedDate) {
        active.filter { it.entryDate == selectedDate }
            .sortedByDescending { it.id }
    }
    val latest = dayEntries.firstOrNull() ?: active.maxWithOrNull(compareBy<JournalSnapshotEntity> { it.entryDate }.thenBy { it.id })
    val press = rememberInkPress()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .inkPressScale(press)
            .clip(shape)
            .background(InkTone.raised)
            .border(1.dp, InkTone.hairline.copy(alpha = 0.65f), shape)
            .inkPressClick(press, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "打开日常记录" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InkSeal("随", sealSize = 34.dp, color = InkTone.jade)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日常记录", style = MaterialTheme.typography.titleMedium, color = InkTone.text)
                if (dayEntries.isNotEmpty()) {
                    Text("${dayEntries.size} 条", style = InkText.caption, color = InkTone.jade)
                } else if (active.isNotEmpty()) {
                    Text("共 ${active.size} 条", style = InkText.caption, color = InkTone.soft)
                }
            }
            Text(
                text = when {
                    dayEntries.isNotEmpty() -> listOfNotNull(latest?.mood?.takeIf { it.isNotBlank() }, latest?.content).joinToString(" · ")
                    latest != null -> "${latest.entryDate} · ${latest.content}"
                    else -> "记下今天的心情、标签与随手想法"
                },
                style = InkText.caption,
                color = if (latest != null) InkTone.soft else InkTone.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("查看", style = InkText.caption.copy(fontWeight = FontWeight.SemiBold), color = InkTone.jade)
    }
}
