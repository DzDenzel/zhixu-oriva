package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 设置备用密码请求。
 */
@Data
public class BackupPasswordSetRequest {

    @NotBlank(message = "备用密码不能为空")
    private String backupPassword;

    private String securityQuestion;

    private String securityAnswer;
}
