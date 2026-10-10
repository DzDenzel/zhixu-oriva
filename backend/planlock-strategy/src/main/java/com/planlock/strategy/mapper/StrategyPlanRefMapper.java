package com.planlock.strategy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只读引用校验：plan_strategies 无 user_id 字段，通过 plans 表校验计划归属。
 * （跨表只读，仅用于归属校验，见 04-技术实现/实现记录）
 */
@Mapper
public interface StrategyPlanRefMapper {

    @Select("SELECT COUNT(*) FROM plans WHERE id = #{planId} AND user_id = #{userId}")
    int countOwnedPlan(@Param("planId") Long planId, @Param("userId") Long userId);
}
