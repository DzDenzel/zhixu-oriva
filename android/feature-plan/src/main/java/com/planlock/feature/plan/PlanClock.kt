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
internal fun rememberPlanClock(): Calendar {
    var clock by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            clock = Calendar.getInstance()
        }
    }
    return clock
}
