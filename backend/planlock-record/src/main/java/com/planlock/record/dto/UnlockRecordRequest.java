package com.planlock.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 解锁记录上报请求。
 */
@Data
public class UnlockRecordRequest {

    @NotNull(message = "计划 ID 不能为空")
    private Long planId;

    @NotNull(message = "解锁时间不能为空")
    private LocalDateTime unlockAt;

    private String method = "backup_password";

    @NotBlank(message = "clientId 不能为空")
    private String clientId;
}
