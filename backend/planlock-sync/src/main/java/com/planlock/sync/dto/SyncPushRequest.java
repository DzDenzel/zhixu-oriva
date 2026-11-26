package com.planlock.sync.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.planlock.common.web.BoundedList;
import com.planlock.common.web.BoundedListDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;

/**
 * 批量推送请求：计划、日程、策略、账号类型与日常记录（记录走 /records/* 幂等补传）。
 */
@Data
public class SyncPushRequest {

    @BoundedList(max = 500)
    @JsonDeserialize(using = BoundedListDeserializer.class)
    private List<PlanItem> plans;

    @BoundedList(max = 500)
    @JsonDeserialize(using = BoundedListDeserializer.class)
    private List<StrategyItem> strategies;

    @BoundedList(max = 500)
    @JsonDeserialize(using = BoundedListDeserializer.class)
    private List<ScheduleItem> schedules;

    @BoundedList(max = 500)
    @JsonDeserialize(using = BoundedListDeserializer.class)
    private List<PlanTypeItem> planTypes;

    @Valid
    @BoundedList(max = 500)
    @JsonDeserialize(using = BoundedListDeserializer.class)
    @Size(max = 500, message = "单次同步日常记录不能超过 500 条")
    private List<JournalItem> journals;

    @Data
    public static class JournalItem {
        @Size(max = 64, message = "clientId 长度超出限制")
        private String clientId;
        private Long id;
        private Long baseVersion;
        private LocalDate entryDate;
        @Size(max = 64, message = "心情长度超出限制")
        private String mood;
        @Size(max = 32, message = "标签列表项数超出限制")
        private List<@Size(max = 64, message = "单个标签长度超出限制") String> tags;
        @Size(max = 20000, message = "正文长度超出限制")
        private String content;
        private Integer status;
    }

    @Data
    public static class PlanTypeItem {
        private String typeKey;
        private String name;
        private String color;
        private Boolean deleted = false;
        private String mutationId;
    }

    @Data
    public static class ScheduleItem {
        private String clientId;
        private Long id; private Long baseVersion; private String title;
        private LocalDate startDate; private LocalTime startTime;
        private LocalDate endDate; private LocalTime endTime;
        private Boolean allDay; private String location; private Double locationLat; private Double locationLon; private String note;
        private Integer remindAdvanceMin; private String repeatRule;
        private Long linkedPlanId; private String tzId; private Integer status;
    }

    @Data
    public static class PlanItem {
        private String clientId;
        private Long id;
        private Long baseVersion;
        private LocalDate endedOccurrenceDate;
        private String statusLog;
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
