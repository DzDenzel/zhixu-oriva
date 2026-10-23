package com.planlock.record.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只读引用校验：记录表通过 plan_id 关联，需校验计划归属用户。
 * （跨表只读，见 04-技术实现/实现记录）
 */
@Mapper
public interface RecordPlanRefMapper {

    /** Allocate record IDs only after earlier uploads for this owner have committed. */
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockOwner(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM plans WHERE id = #{planId} AND user_id = #{userId}")
    int countOwnedPlan(@Param("planId") Long planId, @Param("userId") Long userId);
}
