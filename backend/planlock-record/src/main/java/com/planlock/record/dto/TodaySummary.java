package com.planlock.record.dto;

import com.planlock.record.entity.ExecutionRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 今日计划完成情况。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodaySummary {

    private LocalDate date;
    private List<ExecutionRecord> executions;
    private int violationCount;
    private int unlockCount;
}
