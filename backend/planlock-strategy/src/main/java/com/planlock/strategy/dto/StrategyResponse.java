package com.planlock.strategy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行策略响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyResponse {

    private Long id;
    private Long planId;
    private List<AppItem> blacklistApps;
    private List<AppItem> whitelistApps;
    private Boolean autoDnd;
    private Boolean notifySilence;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
