package com.planlock.sync.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步快照中的执行策略视图（JSON 字段以原始字符串透传）。
 */
@Data
public class SyncStrategyView {

    private Long id;
    private Long planId;
    private String blacklistApps;
    private String whitelistApps;
    private Boolean autoDnd;
    private Boolean notifySilence;
    private Long version;
    private LocalDateTime updatedAt;
}
