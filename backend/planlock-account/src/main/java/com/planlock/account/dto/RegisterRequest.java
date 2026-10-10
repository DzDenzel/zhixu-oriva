package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 注册请求（手机号或邮箱 + 验证码，可附带密码）。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "验证码不能为空")
    private String code;

    private String password;

    @NotBlank(message = "设备标识不能为空")
    private String deviceUid;

    private String deviceName;

    @NotBlank(message = "平台不能为空")
    @Pattern(regexp = "android|ios|windows", message = "平台取值仅支持 android/ios/windows")
    private String platform;
}
