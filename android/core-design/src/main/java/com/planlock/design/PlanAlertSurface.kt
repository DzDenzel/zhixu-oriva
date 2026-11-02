package com.planlock.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** REQ-044 U-R1 / CHANGE-063 闹钟式全屏页的展示数据；业务（响铃、再提醒、收起）由调用方负责。 */
data class PlanAlertModel(
    /** 顶部一行：提前提醒阶段「即将开始」，开始阶段「计划开始了」。 */
    val label: String,
    /** 提前提醒阶段的开始时刻：顶部一行后接逐秒倒计时；null 不显示。 */
    val countdownTo: Long? = null,
    val planName: String,
    /** 「07:00–07:45 · 阅读」 */
    val detail: String,
    /** 计划自己会开启免打扰 / 通知静默时的一行说明，否则 null。 */
    val quietHint: String?,
    /** 只有提前提醒阶段可「5 分钟后再提醒」；计划已开始时只有「我知道了」。 */
    val canSnooze: Boolean,
)

/**
 * 两个候选（UI 设计规范-提醒与胶囊 §1）：CALL 来电式（选定）——计划名居中做主角，两枚全宽大按钮贴底；
 * CLOCK 闹钟式——开始时刻做主角，两枚按钮并排。
 */
enum class PlanAlertLayout { CALL, CLOCK }

@Composable
fun PlanAlertSurface(
    model: PlanAlertModel,
    onSnooze: () -> Unit,
    onAcknowledge: () -> Unit,
    layout: PlanAlertLayout = PlanAlertLayout.CALL,
) {
    Box(
        Modifier.fillMaxSize().background(InkTone.ground).windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        when (layout) {
            PlanAlertLayout.CALL -> CallLayout(model, onSnooze, onAcknowledge)
            PlanAlertLayout.CLOCK -> ClockLayout(model, onSnooze, onAcknowledge)
        }
    }
}

/** 全屏页倒计时：不足 1 小时「mm:ss」，否则「h:mm:ss」；已到点显示「00:00」。 */
fun alertCountdown(remainingMillis: Long): String {
    val total = (remainingMillis.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun PhaseLabel(label: String, countdownTo: Long?) {
    // 页面显示期间应用在前台，逐秒刷新不违背「应用不醒也准」——胶囊与卡片的倒计时仍由系统渲染
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (countdownTo != null) LaunchedEffect(countdownTo) {
        while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) }
    }
    val text = countdownTo?.let { "$label · ${alertCountdown(it - now)}" } ?: label
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(InkTone.accent, CircleShape))
        Text(text, style = InkText.meta, color = InkTone.accent)
    }
}

@Composable
private fun DetailChip(detail: String) {
    if (detail.isBlank()) return
    Text(
        detail, style = MaterialTheme.typography.bodyMedium, color = InkTone.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(InkTone.raised, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun QuietHint(hint: String?) {
    hint ?: return
    Text(hint, style = InkText.meta, color = InkTone.faint, textAlign = TextAlign.Center)
}

@Composable
private fun AcknowledgeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick, modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
    ) { Text("我知道了", style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun SnoozeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick, modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(InkTone.raised, InkTone.text),
    ) { Text("5 分钟后再提醒", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun CallLayout(model: PlanAlertModel, onSnooze: () -> Unit, onAcknowledge: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        PhaseLabel(model.label, model.countdownTo)
        Spacer(Modifier.weight(1f))
        Text(
            model.planName, style = MaterialTheme.typography.displaySmall, color = InkTone.text, textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.padding(top = 16.dp))
        DetailChip(model.detail)
        Spacer(Modifier.padding(top = 12.dp))
        QuietHint(model.quietHint)
        Spacer(Modifier.weight(1.3f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.canSnooze) SnoozeButton(onSnooze, Modifier.fillMaxWidth())
            AcknowledgeButton(onAcknowledge, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ClockLayout(model: PlanAlertModel, onSnooze: () -> Unit, onAcknowledge: () -> Unit) {
    val start = model.detail.substringBefore('–').substringBefore(" · ").takeIf { it.length == 5 && it[2] == ':' }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        PhaseLabel(model.label, model.countdownTo)
        Spacer(Modifier.weight(1f))
        if (start != null) Text(start, style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp), color = InkTone.text)
        Text(
            model.planName, style = MaterialTheme.typography.headlineSmall, color = InkTone.text, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.padding(top = 8.dp))
        Text(model.detail.substringAfter(" · ", ""), style = InkText.meta, color = InkTone.soft)
        Spacer(Modifier.padding(top = 12.dp))
        QuietHint(model.quietHint)
        Spacer(Modifier.weight(1.3f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.canSnooze) SnoozeButton(onSnooze, Modifier.weight(1f))
            AcknowledgeButton(onAcknowledge, Modifier.weight(1f))
        }
    }
}

private val previewModel = PlanAlertModel("即将开始", System.currentTimeMillis() + 15 * 60_000L, "专注阅读", "07:00–07:45 · 阅读",
    "进行中会开启免打扰", canSnooze = true)

@Preview(name = "U-R1 甲 来电式 · 墨序浅色", widthDp = 360, heightDp = 780)
@Composable private fun PreviewCallLight() = PlanLockTheme(ThemeMode.LIGHT) { PlanAlertSurface(previewModel, {}, {}) }

@Preview(name = "U-R1 甲 来电式 · 深色 · 到点开始", widthDp = 360, heightDp = 780)
@Composable private fun PreviewCallDark() = PlanLockTheme(ThemeMode.DARK) {
    PlanAlertSurface(previewModel.copy(label = "计划开始了", countdownTo = null, canSnooze = false, quietHint = null), {}, {})
}

@Preview(name = "U-R1 乙 闹钟式 · 墨序浅色", widthDp = 360, heightDp = 780)
@Composable private fun PreviewClockLight() = PlanLockTheme(ThemeMode.LIGHT) { PlanAlertSurface(previewModel, {}, {}, PlanAlertLayout.CLOCK) }

@Preview(name = "U-R1 甲 来电式 · 320dp 字号 1.3", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable private fun PreviewCallNarrow() = PlanLockTheme(ThemeMode.LIGHT) {
    PlanAlertSurface(previewModel.copy(planName = "周末长跑训练与拉伸放松"), {}, {})
}
