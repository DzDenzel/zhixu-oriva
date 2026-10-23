package com.planlock.app.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Integer evidence, including 0/0 for no scheduled or executed occurrences. */
@Serializable
data class Ratio(val completed: Int = 0, val total: Int = 0) {
    val percent: Int get() = if (total == 0) 0 else (completed.toLong() * 100 / total).toInt()
}

@Serializable
enum class RhythmSource {
    @SerialName("Local") LOCAL,
    @SerialName("Synced") SYNCED,
}

@Serializable
data class RhythmSummary(
    val headline: String,
    val streakDays: Int,
    val completionRate7d: Ratio,
    val completionRatePrev7d: Ratio,
    val focusMinutes30d: Int,
    val focusMinutesPrev30d: Int,
    val earlyEnds30d: Int,
    val earlyEndsPrev30d: Int,
    val days: List<DayCell>,
    val plans: List<PlanPerformance>,
    val distraction: Distraction? = null,
    val source: RhythmSource = RhythmSource.LOCAL,
)

@Serializable
data class DayCell(
    val date: String,
    val planned: Int,
    val completed: Int,
    val earlyEnded: Int,
    val missed: Int,
    val focusMinutes: Int,
    val isToday: Boolean,
)

@Serializable
data class PlanPerformance(
    val planId: Long,
    val name: String,
    val type: String,
    val completionRate30d: Ratio,
    val completed: Int,
    val total: Int,
)

@Serializable
data class Distraction(val minutes7d: Int, val topSources: List<DistractionSource>)

@Serializable
data class DistractionSource(val appName: String, val minutes: Int)

/** Closed behavior evidence, independently synchronized from the removable upload queue. */
@Serializable
data class RhythmBehavior(
    val id: Long = 0,
    val clientId: String? = null,
    val appName: String? = null,
    val packageName: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMs: Long? = null,
    val planId: Long = 0,
    val deviceId: Long = 0,
    val recordAt: String? = null,
    val sourceLevel: String? = null,
)
