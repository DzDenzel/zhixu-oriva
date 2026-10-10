package com.planlock.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量推送响应：逐项结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncPushResponse {

    private List<SyncItemResult> results;
}
