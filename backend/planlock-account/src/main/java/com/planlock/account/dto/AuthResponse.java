package com.planlock.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证响应（游客 / 注册 / 登录统一）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private Long userId;
    private Long deviceId;

    /** 设备此前绑定的匿名用户 ID（登录场景，供客户端调用 /auth/merge；可空）。 */
    private Long guestUserId;
}
