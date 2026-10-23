package com.planlock.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.planlock.app.record.RhythmState

/** Minimal functional binding; the UI task consumes the same full state for calendar and drilldown. */
@Composable
internal fun RhythmReadout(state: RhythmState) {
    state.error?.let { Text(it); return }
    val value = state.summary ?: return
    Column {
        Text(value.headline, style = MaterialTheme.typography.titleMedium)
        Text("连续达成 ${value.streakDays} 天 · 近7天完成率 ${value.completionRate7d.percent}%")
        Text("近30天专注 ${value.focusMinutes30d} 分钟 · 提前结束 ${value.earlyEnds30d} 次")
    }
}
