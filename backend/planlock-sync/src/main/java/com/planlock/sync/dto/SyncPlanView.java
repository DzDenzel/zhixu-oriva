package com.planlock.sync.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.LocalDate;

/**
 * 同步快照中的计划视图（JSON 字段以原始字符串透传）。
 */
@Data
public class SyncPlanView {

    private Long id;
    private String clientId;
    private Long userId;
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
    private Long version;
    private LocalDateTime updatedAt;
}
