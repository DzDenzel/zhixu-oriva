package com.planlock.record.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 周/月统计与连续达成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponse {

    private String period;
    private int continuousDays;
    private int completedCount;
    private int violationCount;
    private long violationDurationMs;
    private List<ViolationAppStat> violationByApp;
    private List<PlanExecutionStat> planExecution;
    private List<EarlyEnding> earlyEndings;
    private int legacyViolationCount;
    private int unlockCount;
    private List<DailyStat> daily;
}
