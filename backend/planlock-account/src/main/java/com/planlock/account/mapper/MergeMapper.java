package com.planlock.account.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 登录数据合并的跨表迁移（account 模块维护）。
 *
 * <p>说明：/auth/merge 语义为「把游客账号数据并入登录账号」。V0.1 数据模型下，
 * 计划（plans）直接以 user_id 归属，策略/记录挂靠在 plan_id / device_id 上（不变），
 * 因此合并只需迁移 plans 归属。此跨模块写表为合并操作的刻意实现，详见 04-技术实现/实现记录。
 */
@Mapper
public interface MergeMapper {

    @Update("UPDATE plans SET user_id = #{toUserId}, version = version + 1 WHERE user_id = #{fromUserId}")
    int transferPlans(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);
}
