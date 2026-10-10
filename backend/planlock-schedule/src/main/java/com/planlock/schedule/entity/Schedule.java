package com.planlock.schedule.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
public class Schedule {
    private Long id;
    private String clientId;
    private Long userId;
    private String title;
    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDate endDate;
    private LocalTime endTime;
    private Boolean allDay;
    private String location;
    private String note;
    private Integer remindAdvanceMin;
    private String repeatRule;
    private Long linkedPlanId;
    private String tzId;
    private Integer status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
