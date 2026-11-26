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

    /** 情景 A：同 client_id 但字段/状态存在任何分歧时无损重命名游客行 client_id，确保双方无损并存。 */
    @Update("UPDATE journal_entries g JOIN journal_entries t "
            + "ON t.user_id = #{toUserId} AND t.client_id = g.client_id "
            + "SET g.client_id = CONCAT('m-', g.id, '-', SUBSTRING(REPLACE(UUID(), '-', ''), 1, 24)) "
            + "WHERE g.user_id = #{fromUserId} AND NOT ("
            + "t.entry_date <=> g.entry_date AND t.mood <=> g.mood "
            + "AND t.tags <=> g.tags AND t.content <=> g.content AND t.status <=> g.status)")
    int rekeyConflictingGuestJournals(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    @Update("UPDATE journal_entries g LEFT JOIN journal_entries t ON t.user_id = #{toUserId} AND t.client_id = g.client_id "
            + "SET g.user_id = #{toUserId}, g.version = g.version + 1 "
            + "WHERE g.user_id = #{fromUserId} AND t.id IS NULL")
    int transferJournals(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    /** 情景 B：仅在全字段与状态完全等价时清理冗余游客行。 */
    @org.apache.ibatis.annotations.Delete("DELETE g FROM journal_entries g JOIN journal_entries t "
            + "ON t.user_id = #{toUserId} AND t.client_id = g.client_id "
            + "WHERE g.user_id = #{fromUserId} "
            + "AND t.entry_date <=> g.entry_date AND t.mood <=> g.mood "
            + "AND t.tags <=> g.tags AND t.content <=> g.content AND t.status <=> g.status")
    int clearDuplicateGuestJournals(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);
}
