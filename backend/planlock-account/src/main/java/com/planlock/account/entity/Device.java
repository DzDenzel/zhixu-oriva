package com.planlock.account.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * devices 表实体（account 模块）。
 */
@Data
public class Device {

    private Long id;
    private Long userId;
    private String deviceName;
    private String platform;
    private String deviceUid;
    private Boolean isPrimary;
    private LocalDateTime lastOnlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 非持久化字段：在线状态（由服务层计算）。 */
    private Boolean online;
}
