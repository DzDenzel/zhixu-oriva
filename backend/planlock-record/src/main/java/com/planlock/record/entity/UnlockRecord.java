package com.planlock.record.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * unlock_records 表实体。
 */
@Data
public class UnlockRecord {

    private Long id;
    private Long planId;
    private Long deviceId;
    private LocalDateTime unlockAt;
    private String method;
    private String clientId;
    private LocalDateTime createdAt;
}
