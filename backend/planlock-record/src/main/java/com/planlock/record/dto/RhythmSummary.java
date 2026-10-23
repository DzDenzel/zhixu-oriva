package com.planlock.record.dto;

import java.util.List;

/** REQ-042 wire contract, shared with core-api/RhythmModels.kt. */
public record RhythmSummary(
        String headline, int streakDays, Ratio completionRate7d, Ratio completionRatePrev7d,
        int focusMinutes30d, int focusMinutesPrev30d, int earlyEnds30d, int earlyEndsPrev30d,
        List<DayCell> days, List<PlanPerformance> plans, Distraction distraction, String source) {
    public record Ratio(int completed, int total) {}
    public record DayCell(String date, int planned, int completed, int earlyEnded, int missed, int focusMinutes, boolean isToday) {}
    public record PlanPerformance(long planId, String name, String type, Ratio completionRate30d, int completed, int total) {}
    public record Distraction(int minutes7d, List<DistractionSource> topSources) {}
    public record DistractionSource(String appName, int minutes) {}
}
