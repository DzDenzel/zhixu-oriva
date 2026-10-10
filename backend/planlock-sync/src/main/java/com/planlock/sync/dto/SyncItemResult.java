package com.planlock.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个推送项的结果（冲突逐项返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncItemResult {

    private String key;
    private boolean ok;
    private int code;
    private String message;
    private Long id;
    private Long version;
    private String clientId;
}
