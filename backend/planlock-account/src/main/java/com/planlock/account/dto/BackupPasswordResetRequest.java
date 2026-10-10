package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 找回备用密码请求（手机号验证码 / 密保问题两种路径，二选一）。
 */
@Data
public class BackupPasswordResetRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    private String code;

    private String securityAnswer;

    @NotBlank(message = "新备用密码不能为空")
    private String newBackupPassword;
}
