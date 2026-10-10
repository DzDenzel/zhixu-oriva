package com.planlock.strategy.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * plan_strategies 表实体（strategy 模块）。blacklist_apps / whitelist_apps 为 JSON 字符串。
 */
@Data
public class PlanStrategy {

    private Long id;
    private Long planId;
    private String blacklistApps;
    private String whitelistApps;
    private Boolean autoDnd;
    private Boolean notifySilence;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
