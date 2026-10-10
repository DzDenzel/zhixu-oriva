package com.planlock.sync.dto;

import lombok.Data;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;

/**
 * 批量推送请求：计划 + 策略（记录走 /records/* 幂等补传）。
 */
@Data
public class SyncPushRequest {

    private List<PlanItem> plans;
    private List<StrategyItem> strategies;
    private List<ScheduleItem> schedules;

    @Data
    public static class ScheduleItem {
        private String clientId;
        private Long id; private Long baseVersion; private String title;
        private LocalDate startDate; private LocalTime startTime;
        private LocalDate endDate; private LocalTime endTime;
        private Boolean allDay; private String location; private String note;
        private Integer remindAdvanceMin; private String repeatRule;
        private Long linkedPlanId; private String tzId; private Integer status;
    }

    @Data
    public static class PlanItem {
        private String clientId;
        private Long id;
        private Long baseVersion;
        private String name;
        private String type;
        private LocalTime startTime;
        private LocalTime endTime;
        private String repeatRule;
        private String color;
        private String icon;
        private Integer remindAdvanceMin;
        private String remindMethods;
        private Integer status;
    }

    @Data
    public static class StrategyItem {
        private Long planId;
        private Long baseVersion;
        private String blacklistApps;
        private String whitelistApps;
        private Boolean autoDnd;
        private Boolean notifySilence;
    }
}
