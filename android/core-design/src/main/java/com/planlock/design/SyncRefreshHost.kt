package com.planlock.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/** Material's release/threshold gesture is shared by the three refreshable pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshHost(
    running: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    errorEventKey: String? = null,
    /** 已退出登录：同步被有意跳过，不是失败——页头显示中性的「未登录 · 登录」，而非黄色失败胶囊（v4.1）。 */
    loginRequired: Boolean = false,
    content: @Composable BoxScope.(status: @Composable () -> Unit) -> Unit,
) {
    val state = rememberPullToRefreshState()
    val refresh by rememberUpdatedState(onRefresh)
    // 新的一次失败：页头圆钮展开成「同步未完成 · 重试」胶囊 5 秒（同一次失败跨页只提示一次，BUG-079）
    var announce by remember { mutableStateOf(false) }
    LaunchedEffect(error, running, errorEventKey) {
        if (!running && error != null && feedbackEvents.consume("sync-refresh", errorEventKey ?: error)) {
            announce = true
            try { kotlinx.coroutines.delay(5_000) } finally { announce = false }
        } else if (running || error == null) announce = false
    }
    // 未登录：每次同步（冷启动 / 下拉）结束后展开几秒，说明「没有同步，因为未登录」；同一次跨页只展开一次。
    var loginAnnounce by remember { mutableStateOf(false) }
    LaunchedEffect(loginRequired, running, errorEventKey) {
        if (!running && loginRequired && error == null && feedbackEvents.consume("sync-login", errorEventKey ?: "login")) {
            loginAnnounce = true
            try { kotlinx.coroutines.delay(4_000) } finally { loginAnnounce = false }
        } else if (running || !loginRequired) loginAnnounce = false
    }
    val openLogin = LocalSyncLoginAction.current
    // v4.1：下拉距离收短——触发阈值 56dp（Material 默认 80dp，过拉可到 160dp，会盖住今日页的钟）；
    // 指示器最多下移到阈值处就停，不再跟手越拉越远。
    Box(modifier.fillMaxSize().pullToRefresh(isRefreshing = running, state = state, threshold = RefreshThreshold, onRefresh = onRefresh)) {
        content {
            StatusRegion {
                when {
                    error != null && !running -> SyncFailureStatus("同步未完成 · 详情", error, announce,
                        onRetry = { announce = false; refresh() })
                    loginRequired && !running -> SyncLoginStatus(loginAnnounce, onLogin = { loginAnnounce = false; openLogin?.invoke() })
                }
            }
        }
        InkRefreshIndicator(state, running, Modifier.align(Alignment.TopCenter))
    }
}

/** 由 App 根部提供：打开账户页登录。未提供时胶囊只作说明。 */
val LocalSyncLoginAction = staticCompositionLocalOf<(() -> Unit)?> { null }

private val RefreshThreshold = 56.dp
private val IndicatorSize = 34.dp

/** 墨序下拉指示：纸面小圆 + 发丝边；下拉时一笔朱色弧随距离画满，松手同步时转成细圈。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InkRefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier) {
    val accent = InkTone.accent
    Box(
        modifier.size(IndicatorSize)
            .graphicsLayer {
                val f = state.distanceFraction.coerceIn(0f, 1f)
                // 最远停在阈值处：圆的底边对齐 56dp，顶部离内容上沿约 22dp
                translationY = f * RefreshThreshold.toPx() - size.height
                alpha = if (refreshing) 1f else (f * 1.8f).coerceIn(0f, 1f)
                val scale = if (refreshing) 1f else .7f + .3f * f
                scaleX = scale; scaleY = scale
            }
            .shadow(3.dp, CircleShape, clip = false)
            .background(InkTone.raised, CircleShape)
            .border(1.dp, InkTone.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Canvas(Modifier.size(16.dp)) {
                val f = state.distanceFraction.coerceIn(0f, 1f)
                val stroke = 2.dp.toPx()
                drawArc(accent.copy(alpha = .25f), 0f, 360f, false, style = Stroke(stroke))
                drawArc(accent, -90f, 360f * f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
