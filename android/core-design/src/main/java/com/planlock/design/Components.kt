package com.planlock.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "今日", Icons.Filled.CalendarToday),
    PLANS("plans", "计划", Icons.AutoMirrored.Filled.EventNote),
    STATS("stats", "统计", Icons.Filled.BarChart),
    SETTINGS("settings", "设置", Icons.Filled.Settings)
}

/**
 * 导航条：一条悬浮的纸色长条，五个等宽位置，布局永远不动。
 * 选中只做两件事：图标与字换成该页的类别色，图标背后淡入同色小底。正中是「知行」标识。
 */
@Composable
fun NavigationBar(
    selectedRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    onAssistant: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val bar = if (dark) InkTone.high else InkTone.raised
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = .08f), spotColor = Color.Black.copy(alpha = .14f))
                .clip(RoundedCornerShape(24.dp))
                .background(bar)
                .border(1.dp, InkTone.hairline.copy(alpha = .7f), RoundedCornerShape(24.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEachIndexed { index, destination ->
                if (index == 2) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        AssistantButton(selectedRoute == "assistant", onAssistant)
                    }
                }
                NavItem(destination, selectedRoute == destination.route, Modifier.weight(1f)) { onSelect(destination) }
            }
        }
    }
}

@Composable
private fun NavItem(destination: TopLevelDestination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val hue = when (destination) {
        TopLevelDestination.TODAY -> InkHue.seal
        TopLevelDestination.PLANS -> InkHue.study
        TopLevelDestination.STATS -> InkHue.work
        TopLevelDestination.SETTINGS -> InkHue.sleep
    }.color()
    val fg by animateColorAsState(if (selected) hue else InkTone.faint, tween(if (reduced) 0 else 220), label = "nav-fg")
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(if (reduced) 0 else 220), label = "nav-glow")
    Column(
        modifier.fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.Tab; this.selected = selected; contentDescription = destination.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = 44.dp, height = 28.dp).clip(CircleShape).background(hue.copy(alpha = .14f * glow)),
            contentAlignment = Alignment.Center) {
            Icon(destination.icon, null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Text(destination.label, color = fg, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AssistantButton(selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "mark-press")
    Box(
        Modifier.size(48.dp).scale(press)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = AssistantIdentity.NAME; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        AssistantMark(markSize = 44.dp, active = selected)
    }
}

enum class PlanVisualState(val label: String) { EXECUTING("进行中"), UPCOMING("未开始"), PAUSED("已暂停"), COMPLETED("已完成") }

data class PlanCardData(
    val id: Long,
    val name: String,
    val type: String,
    val timeText: String,
    val repeatText: String,
    val state: PlanVisualState,
    val supportingText: String? = null
)

@Composable
internal fun stateColor(state: PlanVisualState): Color = when (state) {
    PlanVisualState.EXECUTING -> InkTone.vermilion
    PlanVisualState.UPCOMING -> InkTone.indigo
    PlanVisualState.PAUSED -> Ink.stone
    PlanVisualState.COMPLETED -> InkTone.jade
}

/**
 * 计划卡：类别色的淡底 + 左侧 4dp 色条。名称衬线，起止时刻等宽；进行中换成朱色底并带呼吸点。
 * 同类计划同色，一眼分得清「学习 / 工作 / 运动 / 睡眠」。
 */
@Composable
fun PlanCard(
    data: PlanCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    onOverflow: (() -> Unit)? = null
) {
    val parts = data.timeText.split(" 至 ", " — ", "—")
    val start = parts.getOrNull(0)?.trim().orEmpty()
    val end = parts.getOrNull(1)?.trim()
    val executing = data.state == PlanVisualState.EXECUTING
    val paused = data.state == PlanVisualState.PAUSED
    val hue = if (executing) InkHue.seal else InkHue.forType(data.type)
    val color = hue.color()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (paused) InkTone.sunk else hue.tint())
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (paused) Ink.stone else color))
        // 时间列按内容宽度（下限 40dp）+ 右侧 10dp 间隔：大字体下不再贴住标题
        Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 14.dp, bottom = 14.dp).widthIn(min = 40.dp)) {
            Text(start, style = InkText.meta.copy(fontWeight = FontWeight.Bold), color = if (paused) InkTone.faint else color)
            end?.let { Text(it, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint) }
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(data.name, modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-name" }),
                style = MaterialTheme.typography.titleMedium, color = if (paused) InkTone.soft else InkTone.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.type, style = InkText.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = if (paused) InkTone.faint else color)
                Text(" · ${data.repeatText}  ", style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint, maxLines = 1)
                StatusChip(data.state, Modifier.sharedPlanElement(sharedKey?.let { "$it-status" }, status = true))
            }
            data.supportingText?.let { Text(it, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.vermilion) }
        }
        trailingAction?.invoke()
        onOverflow?.let { action ->
            IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", tint = InkTone.faint, modifier = Modifier.size(18.dp))
            }
        } ?: Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun StatusChip(state: PlanVisualState, modifier: Modifier = Modifier) {
    val color = stateColor(state)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (state == PlanVisualState.EXECUTING) InkPulseDot(color, dotSize = 5.dp)
        else Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(" ${state.label}", style = InkText.meta.copy(fontSize = 10.sp), color = color)
    }
}

/**
 * 执行条：不再是一整块墨，而是一张轻纸——一行状态、衬线倒计时、一根朱色进度细线、一个文字动作。
 */
@Composable
fun ExecutionStatusCard(
    planName: String,
    remainingText: String,
    endsAtText: String,
    progress: Float,
    paused: Boolean,
    onTogglePause: () -> Unit,
    sharedKey: String? = null,
    sharedDuration: Int = 280
) {
    val reduced = LocalReducedMotion.current
    val accent = if (paused) InkTone.brass else InkTone.vermilion
    val grow by animateFloatAsState(progress.coerceIn(0f, 1f), if (reduced) snap() else tween(800, easing = Ink.Easing), label = "exec-progress")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(accent.inkTint())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(paused, transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)).togetherWith(fadeOut(tween(if (reduced) 0 else 200))) }, label = "execution-state") { isPaused ->
                if (isPaused) Box(Modifier.padding(2.dp).size(7.dp).background(accent, RoundedCornerShape(1.dp)))
                else InkPulseDot(accent, dotSize = 6.dp)
            }
            Text(if (paused) " 已暂停" else " 进行中",
                modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-status" }, true, sharedDuration),
                color = accent, style = InkText.meta.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text(endsAtText, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint, maxLines = 1)
        }
        Text(planName, modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-name" }, duration = sharedDuration),
            color = InkTone.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(remainingText, color = InkTone.text, style = V2Tokens.numeralLarge.themed().copy(fontSize = 38.sp, lineHeight = 42.sp),
                modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = onTogglePause) {
                Text(if (paused) "继续" else "暂停", color = accent, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(InkTone.hairline)) {
            Box(Modifier.fillMaxWidth(grow).height(2.dp).background(accent))
        }
    }
}

/** 数据签：类别色淡底 + 衬线数字 + 两行小字。不传颜色时用纸色。 */
@Composable
fun StatTile(value: String, label: String, trend: String, danger: Boolean = false, modifier: Modifier = Modifier, hue: Color? = null) {
    val accent = if (danger) InkTone.crimson else hue ?: InkTone.text
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(if (danger) InkTone.crimson.inkTint() else hue?.inkTint() ?: InkTone.raised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = InkText.numeral.copy(fontSize = 28.sp), color = accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = InkTone.text, maxLines = 1)
        Text(trend, style = InkText.meta.copy(fontSize = 10.sp), color = InkTone.faint, maxLines = 1)
    }
}

enum class UiState { LOADING, EMPTY, ERROR }

internal data class UiStateCopy(val title: String, val actionLabel: String) {
    companion object {
        fun resolve(state: UiState, emptySubject: String = "计划"): UiStateCopy = when (state) {
            UiState.LOADING -> UiStateCopy("正在同步", "")
            UiState.EMPTY -> UiStateCopy("还没有$emptySubject", "创建第一个$emptySubject")
            UiState.ERROR -> UiStateCopy("加载失败", "重试")
        }
    }
}

/** 状态面板：一句话 + 一个文字动作。加载时是三条淡淡的呼吸线。 */
@Composable
fun UIStatePanel(
    state: UiState,
    message: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    emptySubject: String = "计划",
) {
    val copy = UiStateCopy.resolve(state, emptySubject)
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state == UiState.LOADING) {
            val reduced = LocalReducedMotion.current
            val shimmer = if (reduced) .6f else rememberInfiniteTransition(label = "loading").animateFloat(
                .3f, .8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "loading-alpha").value
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(.62f, 1f, .8f).forEach { width ->
                    Box(Modifier.fillMaxWidth(width).height(12.dp).alpha(shimmer).background(InkTone.sunk, CircleShape))
                }
            }
            return@Column
        }
        if (state == UiState.ERROR) EnsoMark(isError = true, modifier = Modifier.size(56.dp))
        Text(copy.title, style = MaterialTheme.typography.titleMedium, color = InkTone.text)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = InkTone.soft) }
        onAction?.let {
            TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(if (state == UiState.ERROR) Icons.Filled.Refresh else Icons.Filled.Add, null, tint = InkTone.vermilion, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(copy.actionLabel, color = InkTone.vermilion, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

data class PermissionItem(
    val title: String,
    val enabled: Boolean? = null,
    val required: Boolean = true,
    val consequence: String? = null,
    val statusText: String? = null,
    val actionText: String? = null,
)

/** 引导步进：一根细线 + 已走过的朱色段，下方等宽「n / N」。 */
@Composable
fun StepIndicator(currentStep: Int, totalSteps: Int = 4) {
    val accent = InkTone.vermilion
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(totalSteps) { index ->
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape)
                    .background(if (index < currentStep) accent else InkTone.hairline))
            }
        }
        Text("$currentStep / $totalSteps", style = InkText.meta, color = InkTone.faint)
    }
}

data class AppOption(val id: String, val name: String, val category: String, val icon: ImageBitmap? = null)

@Composable
internal fun AppRow(app: AppOption, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    val controls = LocalSecondaryControlColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { if (locked) stateDescription = "已锁定" }
            .clickable(onClick = onClick)
            .alpha(if (locked) .48f else 1f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(InkTone.sunk), contentAlignment = Alignment.Center) {
            if (app.icon != null) Image(app.icon, app.name, Modifier.size(28.dp))
            else Text(app.name.take(1), fontFamily = InkTone.display, fontWeight = FontWeight.Bold, color = InkTone.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.category, style = InkText.meta.copy(fontSize = 11.sp), color = InkTone.faint)
        }
        if (locked) {
            Icon(Icons.Filled.Lock, contentDescription = "已锁定", modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(checkedColor = controls.accent, checkmarkColor = controls.onAccent),
        )
    }
}
