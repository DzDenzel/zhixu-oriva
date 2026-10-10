package com.planlock.plan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 计划响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {

    private Long id;
    private String clientId;
    private Long userId;
    private String name;
    private String type;
    private LocalTime startTime;
    private LocalTime endTime;
    private RepeatRule repeatRule;
    private String color;
    private String icon;
    private Integer remindAdvanceMin;
    private RemindMethods remindMethods;
    private Integer status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
