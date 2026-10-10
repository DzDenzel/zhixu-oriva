package com.planlock.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 执行记录上报请求（离线补传，client_id 幂等）。
 */
@Data
public class ExecutionRecordRequest {

    @NotNull(message = "计划 ID 不能为空")
    private Long planId;

    @NotNull(message = "计划日期不能为空")
    private LocalDate planDate;

    @NotNull(message = "执行状态不能为空")
    private Integer status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @NotBlank(message = "clientId 不能为空")
    private String clientId;
}
