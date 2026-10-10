package com.planlock.account.dto;

import lombok.Data;

/**
 * 登录后合并本地数据请求：携带设备此前绑定的匿名用户 ID。
 */
@Data
public class MergeRequest {

    private Long guestUserId;
}
