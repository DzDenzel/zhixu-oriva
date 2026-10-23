package com.planlock.feature.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
internal fun rememberPlanClock(boundaryOffsetMillis: Long = 0L): Calendar {
    var clock by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(boundaryOffsetMillis) {
        while (true) {
            // Plan boundaries and the displayed countdown have minute precision.
            // Align updates to that boundary instead of drifting from page entry time.
            delay(60_000L - (System.currentTimeMillis() - boundaryOffsetMillis) % 60_000L)
            clock = Calendar.getInstance()
        }
    }
    return clock
}
