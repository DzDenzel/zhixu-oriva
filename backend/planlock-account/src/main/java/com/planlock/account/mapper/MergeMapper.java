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

    @org.apache.ibatis.annotations.Insert("INSERT INTO plan_types(user_id,type_key,name,color,version,deleted,mutation_id) SELECT #{toUserId},source.type_key,source.name,source.color,source.version+1,source.deleted,source.mutation_id FROM plan_types source WHERE source.user_id=#{fromUserId} ON DUPLICATE KEY UPDATE deleted=GREATEST(plan_types.deleted,VALUES(deleted)),version=plan_types.version+1")
    int transferPlanTypes(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM plan_types WHERE user_id=#{fromUserId}")
    int clearGuestPlanTypes(@Param("fromUserId") Long fromUserId);

    @Update("UPDATE plans p JOIN plan_types t ON p.user_id=t.user_id AND p.type=t.type_key SET p.type='其他',p.version=p.version+1 WHERE t.user_id=#{userId} AND t.deleted=1")
    int reassignDeletedPlanTypes(@Param("userId") Long userId);

    @Update("UPDATE plans SET user_id = #{toUserId}, version = version + 1 WHERE user_id = #{fromUserId}")
    int transferPlans(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE schedules SET user_id = #{toUserId}, version = version + 1 WHERE user_id = #{fromUserId}")
    int transferSchedules(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);
}
