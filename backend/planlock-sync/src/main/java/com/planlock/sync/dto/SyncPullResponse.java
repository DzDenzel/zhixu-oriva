package com.planlock.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 全量拉取响应：计划、日程、策略、账号类型（含墓碑）与记录摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncPullResponse {

    private List<SyncPlanView> plans;
    private List<SyncScheduleView> schedules;
    private List<SyncStrategyView> strategies;
    private Map<String, Integer> recordCounts;
    private List<SyncPlanTypeView> planTypes;
}
