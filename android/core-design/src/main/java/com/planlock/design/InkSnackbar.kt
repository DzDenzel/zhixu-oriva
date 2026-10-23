package com.planlock.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 提示条的语气：按文案判断，决定左侧色条的颜色。 */
enum class InkNoticeTone { NEUTRAL, DONE, WARN }

fun inkNoticeToneOf(message: String): InkNoticeTone = when {
    listOf("失败", "未完成", "未授权", "未生效", "错误", "无法", "不可用").any { it in message } -> InkNoticeTone.WARN
    listOf("成功", "完成", "已保存", "已开始", "已结束").any { it in message } -> InkNoticeTone.DONE
    else -> InkNoticeTone.NEUTRAL
}

/**
 * 墨序提示条（v3.5）：取代 Material 默认的黑色 Snackbar。
 * 纸面浮片 + 发丝边 + 左侧语气色条（琥珀 = 未完成 / 失败，草绿 = 完成，墨 = 一般）；
 * 动作用朱色文字，关闭为淡墨 ×。所有页面通过 [InkSnackbarHost] 使用。
 */
@Composable
fun InkSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> InkSnackbar(data) }
}

@Composable
fun InkSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val tone = inkNoticeToneOf(visuals.message)
    val accent = when (tone) {
        InkNoticeTone.WARN -> InkHue.amber.color()
        InkNoticeTone.DONE -> InkHue.sport.color()
        InkNoticeTone.NEUTRAL -> InkTone.soft
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = InkTone.raised,
        border = BorderStroke(1.dp, InkTone.hairline),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = .9f)))
            Spacer(Modifier.width(10.dp))
            Text(
                visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkTone.text,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = InkTone.vermilion, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            if (visuals.withDismissAction) {
                TextButton(
                    onClick = { data.dismiss() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "关闭提示" },
                ) {
                    Text("×", color = InkTone.faint, fontSize = 18.sp)
                }
            } else Spacer(Modifier.width(12.dp))
        }
    }
}

