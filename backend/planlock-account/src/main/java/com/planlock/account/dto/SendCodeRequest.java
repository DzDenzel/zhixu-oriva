package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 验证码下发请求（对应 接口设计 v0.1.1 POST /auth/code/send）。
 */
@Data
public class SendCodeRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "register|login|reset_backup_password",
            message = "场景取值仅支持 register/login/reset_backup_password")
    private String scene;
}
