package com.planlock.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * 编辑页表单（v4.4，BUG-112 修订：负责人 2026-10-19「不用弹窗，出个提醒就行」）：
 * - [InkSaveBar] 贴底操作栏：纸色底 + 顶部发丝线 + 全宽主按钮。放在页面最底（Scaffold bottomBar 或 Column 末尾），
 *   根部已让出输入法高度，所以它随键盘上移；内容区在它上方滚动。
 * - 点保存先校验：不通过时不弹窗——页面直接滚到第一项、可输入的给焦点，并用 [inkAttention] 描边闪 1.5 秒；
 *   同时保存按钮上方出现一行提醒（[saveBarHint]），随填写实时更新，全部填好即消失；点这行再跳到第一项。
 */

/** 一条未通过的校验：[field] 是页面内的字段键（用于滚动与高亮），[message] 是给人看的一句话。 */
data class FormIssue(val field: String, val message: String)

/** 保存栏上方的一行：「请填写计划名称」「请填写日程标题，还有 2 项」；没有问题时为 null。 */
fun saveBarHint(issues: List<FormIssue>): String? = when (issues.size) {
    0 -> null
    1 -> issues.first().message
    else -> "${issues.first().message}，还有 ${issues.size - 1} 项"
}

/** 输入法是否弹起（只看高度，不依赖实验 API）。 */
@Composable
fun imeVisible(): Boolean =
    WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

/** 保存栏上方提醒的停留时长（毫秒）。 */
const val SAVE_HINT_VISIBLE_MS = 4_000L

/**
 * [hint] 非空时在按钮上方显示一行错误色提醒（读屏会播报）；[onHint] 点这行时调用（通常是跳到第一项）。
 * 提醒不常驻：每次点保存后出现，[SAVE_HINT_VISIBLE_MS] 后自动收起；再点保存会重新出现。
 */
@Composable
fun InkSaveBar(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    hint: String? = null,
    onHint: (() -> Unit)? = null,
) {
    // 每点一次保存记一次，用来让提醒重新出现并重新计时
    val attempt = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val showing = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(attempt.intValue, hint != null) {
        if (hint == null) { showing.value = false; return@LaunchedEffect }
        showing.value = true
        kotlinx.coroutines.delay(SAVE_HINT_VISIBLE_MS)
        showing.value = false
    }
    // v4.6（真机，负责人 10-20）：输入法弹起时收起整条保存栏，不再贴在键盘上方和输入框挤在一起；收起键盘后回来
    if (imeVisible()) return
    Column(modifier.fillMaxWidth().background(InkTone.ground)) {
        HorizontalDivider(color = InkTone.hairline)
        androidx.compose.animation.AnimatedVisibility(visible = hint != null && showing.value) {
            // 退场动画期间 hint 已为 null，保留最后一句，避免文字先消失
            val last = remember { androidx.compose.runtime.mutableStateOf(hint.orEmpty()) }
            if (hint != null) last.value = hint
            Row(
                Modifier.fillMaxWidth()
                    .then(if (onHint != null) Modifier.clickable(onClickLabel = "跳到要填写的地方", onClick = onHint) else Modifier)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                Text(last.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                if (onHint != null) Text("去填写", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            onClick = { attempt.intValue++; onClick() },
            enabled = enabled && !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 52.dp),
        ) { Text(if (busy) "保存中…" else label) }
    }
}

/**
 * 跳到某一项后的一次提醒：[token] 每变一次（> 0），描边以错误色亮起、停约 1 秒后在 0.5 秒内淡出，合计 1.5 秒。
 * 减弱动画时直接亮起再在 1.5 秒后消失，不做渐变。
 */
@Composable
fun Modifier.inkAttention(token: Int, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp): Modifier {
    val alpha = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val color = MaterialTheme.colorScheme.error
    LaunchedEffect(token) {
        if (token <= 0) return@LaunchedEffect
        alpha.snapTo(1f)
        if (reduced) { kotlinx.coroutines.delay(1500); alpha.snapTo(0f) }
        else { kotlinx.coroutines.delay(1000); alpha.animateTo(0f, tween(500)) }
    }
    return this.drawWithContent {
        drawContent()
        if (alpha.value > 0f) {
            val w = 1.5.dp.toPx()
            drawRoundRect(color.copy(alpha = alpha.value), topLeft = androidx.compose.ui.geometry.Offset(-w, -w),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * w, size.height + 2 * w),
                cornerRadius = CornerRadius(cornerRadius.toPx() + w), style = Stroke(width = w * 2))
        }
    }
}

/**
 * 页面内字段的定位表：每个字段用 [inkFormField] 登记，校验不通过时 [reveal] 把它滚进可视区、
 * 可输入的字段拿到焦点，并触发一次 [inkAttention]。
 */
class InkFormTargets internal constructor() {
    internal val bring = mutableMapOf<String, androidx.compose.foundation.relocation.BringIntoViewRequester>()
    internal val focus = mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>()
    internal val flash = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    suspend fun reveal(field: String) {
        bring[field]?.bringIntoView()
        focus[field]?.let { runCatching { it.requestFocus() } }
        flash[field] = (flash[field] ?: 0) + 1
    }
}

@Composable
fun rememberInkFormTargets(): InkFormTargets = remember { InkFormTargets() }

/** 登记一个字段：可滚动定位、描边提醒；[focusable] 为 true 时（文本框）跳过来会把焦点给它。 */
@Composable
fun Modifier.inkFormField(targets: InkFormTargets, field: String, focusable: Boolean = false, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp): Modifier {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.DisposableEffect(targets, field) {
        targets.bring[field] = bring
        if (focusable) targets.focus[field] = focus
        onDispose {
            if (targets.bring[field] === bring) targets.bring.remove(field)
            if (targets.focus[field] === focus) targets.focus.remove(field)
        }
    }
    val base = this.bringIntoViewRequester(bring).inkAttention(targets.flash[field] ?: 0, cornerRadius)
    return if (focusable) base.focusRequester(focus) else base
}
