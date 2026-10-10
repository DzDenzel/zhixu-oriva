package com.planlock.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 游客设备注册请求。
 */
@Data
public class DeviceRegisterRequest {

    @NotBlank(message = "设备标识不能为空")
    private String deviceUid;

    private String deviceName;

    @NotBlank(message = "平台不能为空")
    @Pattern(regexp = "android|ios|windows", message = "平台取值仅支持 android/ios/windows")
    private String platform;
}
