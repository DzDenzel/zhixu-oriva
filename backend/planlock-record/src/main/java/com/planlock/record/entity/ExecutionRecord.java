package com.planlock.record.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * execution_records 表实体。status：1 完成 / 2 提前解锁 / 3 违规结束。
 */
@Data
public class ExecutionRecord {

    private Long id;
    private Long planId;
    private Long deviceId;
    private LocalDate planDate;
    private Integer status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String clientId;
    private LocalDateTime createdAt;
}
