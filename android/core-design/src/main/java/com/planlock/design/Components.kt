package com.planlock.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "今日", Icons.Filled.CalendarToday),
    PLANS("plans", "计划", Icons.AutoMirrored.Filled.EventNote),
    STATS("stats", "统计", Icons.Filled.BarChart),
    SETTINGS("settings", "设置", Icons.Filled.Settings)
}

@Composable
fun NavigationBar(
    selectedRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    onAssistant: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(92.dp)) {
    androidx.compose.material3.NavigationBar(
        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TopLevelDestination.entries.forEachIndexed { index, destination ->
            if (index == 2) Spacer(Modifier.width(72.dp))
            NavigationBarItem(
                selected = selectedRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    }
    Surface(
        onClick = onAssistant,
        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-4).dp).size(64.dp)
            .shadow(V2Tokens.e3.y, CircleShape, ambientColor = V2Tokens.brandGlow, spotColor = V2Tokens.brandGlow),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("✦", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
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
fun PlanCard(
    data: PlanCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    onOverflow: (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large
    val lift = if (data.state == PlanVisualState.EXECUTING && !LocalDarkTheme.current) Modifier
        .shadow(2.dp, shape, ambientColor = PlanLockColors.Brand.copy(alpha = .07f), spotColor = PlanLockColors.Brand.copy(alpha = .10f))
        .shadow(8.dp, shape, ambientColor = Color.Transparent, spotColor = PlanLockColors.Brand.copy(alpha = .04f))
    else Modifier
    Card(
        modifier = modifier.then(lift).fillMaxWidth().clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (data.state == PlanVisualState.EXECUTING) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = if (data.state == PlanVisualState.EXECUTING && !LocalDarkTheme.current) 1.dp else 0.dp),
        border = if (data.state == PlanVisualState.EXECUTING) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(data.state, Modifier.sharedPlanElement(sharedKey?.let { "$it-status" }, status = true))
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(data.type) })
                Spacer(Modifier.weight(1f))
                trailingAction?.invoke()
                onOverflow?.let { action ->
                    IconButton(onClick = action, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                }
            }
            Text(data.name, modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-name" }), style = MaterialTheme.typography.titleMedium)
            Text("${data.timeText} · ${data.repeatText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            data.supportingText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StatusChip(state: PlanVisualState, modifier: Modifier = Modifier) {
    val color = when (state) {
        PlanVisualState.EXECUTING -> PlanLockColors.Executing
        PlanVisualState.UPCOMING -> PlanLockColors.Upcoming
        PlanVisualState.PAUSED -> PlanLockColors.Paused
        PlanVisualState.COMPLETED -> PlanLockColors.Completed
    }
    Surface(modifier = modifier, color = color, contentColor = Color.White, shape = MaterialTheme.shapes.small) {
        Text(state.label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val reducedMotion = LocalReducedMotion.current
    val container by animateColorAsState(
        targetValue = if (paused) MaterialTheme.colorScheme.surfaceContainerLow else PlanLockColors.Brand,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(), label = "execution-card"
    )
    val foreground = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    val shape = RoundedCornerShape(topStart = V2Tokens.shapeXl, topEnd = V2Tokens.shapeXl, bottomEnd = V2Tokens.shapeSm, bottomStart = V2Tokens.shapeXl)
    // 两层平台阴影叠加，不在帧循环里生成位图；深色仅用色面。
    val shadow = if (LocalDarkTheme.current) Modifier else Modifier
        .shadow(4.dp, shape, clip = false, ambientColor = PlanLockColors.Brand.copy(alpha = .10f), spotColor = PlanLockColors.Brand.copy(alpha = .16f))
        .shadow(12.dp, shape, clip = false, ambientColor = Color.Transparent, spotColor = PlanLockColors.Brand.copy(alpha = .07f))
    val texture = if (paused) Modifier.background(container) else Modifier.background(
        Brush.linearGradient(listOf(V2Tokens.brand, V2Tokens.brandDeep))
    )
    Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth().then(shadow)) {
        Column(Modifier.fillMaxWidth().then(texture).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedContent(paused, transitionSpec = { fadeIn(tween(if (reducedMotion) 0 else 240)).togetherWith(fadeOut(tween(if (reducedMotion) 0 else 240))) }, label = "execution-state") { isPaused ->
                    Icon(if (isPaused) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, null, Modifier.size(18.dp), tint = foreground)
                }
                Text(if (paused) "已暂停" else "● 执行中", modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-status" }, true, sharedDuration), color = foreground, style = MaterialTheme.typography.labelLarge)
            }
            Text(planName, modifier = Modifier.sharedPlanElement(sharedKey?.let { "$it-name" }, duration = sharedDuration), color = foreground, style = MaterialTheme.typography.titleLarge)
            Text(remainingText, color = foreground, style = V2Tokens.numeralHero)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (paused) MaterialTheme.colorScheme.outline else V2Tokens.brandContainer,
                trackColor = if (paused) MaterialTheme.colorScheme.surfaceContainerHigh else V2Tokens.brandBright,
            )
            Text(endsAtText, color = foreground.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Button(
                onClick = onTogglePause,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (paused) MaterialTheme.colorScheme.surface else PlanLockColors.BrandContainer,
                    contentColor = if (paused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(if (paused) "继续计划" else "暂停计划")
            }
        }
    }
}

@Composable
fun StatTile(value: String, label: String, trend: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(trend, style = MaterialTheme.typography.labelMedium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

@Composable
fun UIStatePanel(
    state: UiState,
    message: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    emptySubject: String = "计划",
) {
    val copy = UiStateCopy.resolve(state, emptySubject)
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state == UiState.LOADING) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(1f, .72f, .88f).forEach { width ->
                    Box(Modifier.fillMaxWidth(width).height(24.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small))
                }
            }
        } else StateIllustration(isError = state == UiState.ERROR)
        Text(copy.title, style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        onAction?.let {
            Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), onClick = it, modifier = Modifier.height(48.dp)) {
                Icon(if (state == UiState.ERROR) Icons.Filled.Refresh else Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp)); Text(copy.actionLabel)
            }
        }
    }
}

@Composable
private fun StateIllustration(isError: Boolean) {
    val outline = PlanLockColors.Brand
    val alert = MaterialTheme.colorScheme.error
    Canvas(Modifier.size(84.dp)) {
        val stroke = Stroke(width = 4.dp.toPx())
        drawRoundRect(
            color = outline,
            topLeft = Offset(size.width * .16f, size.height * .12f),
            size = Size(size.width * .68f, size.height * .76f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            style = stroke
        )
        drawLine(outline, Offset(size.width * .29f, size.height * .34f), Offset(size.width * .71f, size.height * .34f), stroke.width)
        drawLine(outline, Offset(size.width * .29f, size.height * .50f), Offset(size.width * .62f, size.height * .50f), stroke.width)
        drawLine(outline, Offset(size.width * .29f, size.height * .66f), Offset(size.width * .52f, size.height * .66f), stroke.width)
        if (isError) {
            drawCircle(alert, radius = size.width * .16f, center = Offset(size.width * .76f, size.height * .76f))
            drawLine(Color.White, Offset(size.width * .76f, size.height * .69f), Offset(size.width * .76f, size.height * .77f), 3.dp.toPx())
            drawCircle(Color.White, radius = 1.8.dp.toPx(), center = Offset(size.width * .76f, size.height * .82f))
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

@Composable
fun StepIndicator(currentStep: Int, totalSteps: Int = 4) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            repeat(totalSteps) { index ->
                val selected = index + 1 <= currentStep
                Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        LinearProgressIndicator(progress = { currentStep.toFloat() / totalSteps }, modifier = Modifier.fillMaxWidth())
        Text("第 $currentStep / $totalSteps 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            if (app.icon != null) Image(app.icon, app.name, Modifier.size(28.dp)) else Text(app.name.take(1), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
