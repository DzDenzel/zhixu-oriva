package com.planlock.strategy.dto;

import lombok.Data;

import java.util.List;

/**
 * 执行策略创建/更新请求（PUT 更新时另行携带 baseVersion 查询参数）。
 */
@Data
public class StrategyUpsertRequest {

    private List<AppItem> blacklistApps;
    private List<AppItem> whitelistApps;
    private Boolean autoDnd;
    private Boolean notifySilence;
}
