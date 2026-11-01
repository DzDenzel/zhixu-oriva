package com.planlock.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.planlock.app.api.Insight
import com.planlock.app.api.InsightFeedback
import com.planlock.app.api.InsightReference
import com.planlock.app.api.InsightSource
import com.planlock.app.api.InsightStage
import com.planlock.app.api.InsightStatus
import com.planlock.design.InkHue
import com.planlock.design.InkText
import com.planlock.design.InkTone
import com.planlock.design.PlanLockTheme
import com.planlock.design.ThemeMode
import com.planlock.design.color

/** 页面里实际出现的条目：已否决的不再显示（REQ-042 G4）。 */
internal fun visibleInsights(items: List<Insight>): List<Insight> = items.filter { it.status != InsightStatus.REJECTED }

/** 行内动作：只有待确认的条目带「不像 / 像我」（顺序即显示顺序）。 */
internal fun insightActions(insight: Insight): List<InsightFeedback> =
    if (insight.status == InsightStatus.PENDING) listOf(InsightFeedback.UNLIKE_ME, InsightFeedback.LIKE_ME) else emptyList()

internal fun insightActionLabel(feedback: InsightFeedback): String = when (feedback) {
    InsightFeedback.UNLIKE_ME -> "不像"
    InsightFeedback.LIKE_ME -> "像我"
}

/**
 * REQ-042 G1「知行对你的了解」：放在「我的节奏」各卡之后，紧凑提醒式小条（小字、单行为主）；
 * 待确认的条目行内带「不像 / 像我」。**没有条目时整块不渲染**——不占位、不写提示（CHANGE-061 裁决丁）。
 */
@Composable
fun InsightBlock(items: List<Insight>, onFeedback: (Insight, InsightFeedback) -> Unit) {
    val shown = visibleInsights(items)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("insight-block"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("知行对你的了解", style = InkText.caption, color = InkTone.soft, modifier = Modifier.padding(start = 4.dp))
        shown.forEach { InsightStrip(it, onFeedback) }
    }
}

@Composable
private fun InsightStrip(insight: Insight, onFeedback: (Insight, InsightFeedback) -> Unit) {
    val pending = insight.status == InsightStatus.PENDING
    Row(
        Modifier.fillMaxWidth().background(InkTone.raised, RoundedCornerShape(14.dp)).heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("insight-${insight.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (pending) InkHue.amber.color() else InkHue.sport.color(), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(insight.text, style = MaterialTheme.typography.bodyMedium, color = InkTone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (pending) "待确认 · ${insight.evidence}" else "已确认 · ${insight.evidence}",
                style = InkText.caption, color = InkTone.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        insightActions(insight).forEach { feedback ->
            TextButton(onClick = { onFeedback(insight, feedback) }, Modifier.heightIn(min = 48.dp)) {
                Text(insightActionLabel(feedback), color = if (feedback == InsightFeedback.LIKE_ME) InkTone.accent else InkTone.soft)
            }
        }
    }
}

/** C4 夹具：1 条待确认 + 1 条已确认。只用于预览与界面测试，生产数据只来自 InsightRepository。 */
internal fun insightFixture(scope: String = "local\nlocal"): List<Insight> = listOf(
    Insight("fixture-1", scope, "目前看来，你似乎更习惯晚饭后读书", InsightStatus.PENDING, InsightStage.FIRST_IMPRESSION,
        "依据：你说过的 2 句话", listOf(InsightReference("c1", "m1")), InsightSource.CONVERSATION, "fixture", 0, 0),
    Insight("fixture-2", scope, "你希望周末不排早起的计划", InsightStatus.CONFIRMED, InsightStage.FIRST_IMPRESSION,
        "依据：你亲口确认", listOf(InsightReference("c1", "m2")), InsightSource.CONVERSATION, "fixture", 0, 0),
)

@Preview(name = "C4 空列表：区块不存在", widthDp = 360, heightDp = 120)
@Composable private fun PreviewInsightEmpty() = PlanLockTheme(ThemeMode.LIGHT) { InsightBlock(emptyList()) { _, _ -> } }

@Preview(name = "C4 夹具 2 条：1 待确认 1 已确认", widthDp = 360)
@Composable private fun PreviewInsightFixture() = PlanLockTheme(ThemeMode.LIGHT) { InsightBlock(insightFixture()) { _, _ -> } }

@Preview(name = "C4 夹具 2 条 · 深色", widthDp = 360)
@Composable private fun PreviewInsightFixtureDark() = PlanLockTheme(ThemeMode.DARK) { InsightBlock(insightFixture()) { _, _ -> } }
