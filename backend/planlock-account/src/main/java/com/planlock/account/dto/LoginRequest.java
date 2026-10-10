package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录请求（手机号或邮箱 + 密码 / 验证码）。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    private String password;

    private String code;

    @NotBlank(message = "设备标识不能为空")
    private String deviceUid;

    private String deviceName;

    @NotBlank(message = "平台不能为空")
    @Pattern(regexp = "android|ios|windows", message = "平台取值仅支持 android/ios/windows")
    private String platform;
}
