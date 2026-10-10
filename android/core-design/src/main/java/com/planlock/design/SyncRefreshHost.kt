package com.planlock.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material's release/threshold gesture is shared by the three refreshable pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshHost(
    running: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    errorEventKey: String? = null,
    content: @Composable BoxScope.(status: @Composable () -> Unit) -> Unit,
) {
    val state = rememberPullToRefreshState()
    val snackbar = remember { SnackbarHostState() }
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(error, running, errorEventKey) {
        if (!running && error != null && feedbackEvents.consume("sync-refresh", errorEventKey ?: error)) {
            if (snackbar.showSnackbar(error, actionLabel = "重试", withDismissAction = true,
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) refresh()
        }
    }
    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = running, onRefresh = onRefresh, state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            content {
                StatusRegion {
                    if (error != null && !running) PersistentStatus("同步未完成 · 详情", error, compact = true)
                }
            }
        }
        // Keep retry/dismiss reachable above the page's floating creation action.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
}
