package com.planlock.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行期其他应用使用区间；新增字段均可空以兼容 v1 客户端。
 */
@Data
public class ViolationRecordRequest {

    @NotNull(message = "计划 ID 不能为空")
    private Long planId;

    @NotNull(message = "违规时间不能为空")
    private LocalDateTime recordAt;

    private String appName;

    private String packageName;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private Long durationMs;

    private String sourceLevel;

    @NotBlank(message = "clientId 不能为空")
    private String clientId;
}
