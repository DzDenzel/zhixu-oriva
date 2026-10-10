package com.planlock.plan.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * plans 表实体（plan 模块）。repeat_rule / remind_methods 为 JSON 字符串。
 */
@Data
public class Plan {

    private Long id;
    private String clientId;
    private Long userId;
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
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
