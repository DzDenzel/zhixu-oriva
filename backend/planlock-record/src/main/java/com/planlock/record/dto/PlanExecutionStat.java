package com.planlock.record.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanExecutionStat {
    private Long planId;
    private int completedCount;
    private int earlyEndedCount;
    private long actualDurationMs;
}
