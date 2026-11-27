package com.planlock.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前认证主体与个人后端实例只读摘要（GET /api/v1/me）。
 * 严禁包含密码哈希、备用密码、密保答案、原始完整敏感凭据或部署初始化口令。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeResponse {

    private Long userId;
    private Long deviceId;
    private String accountMasked;
    private String userType;
    private boolean guest;
    private String deviceName;
    private String devicePlatform;
    private String instanceLabel;
    private boolean deploymentEnabled;
    private String version;
}
