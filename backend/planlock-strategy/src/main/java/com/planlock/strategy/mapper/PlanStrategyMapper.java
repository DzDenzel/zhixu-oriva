package com.planlock.strategy.mapper;

import com.planlock.strategy.entity.PlanStrategy;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * plan_strategies 表 Mapper（strategy 模块）。
 */
@Mapper
public interface PlanStrategyMapper {

    @Select("SELECT * FROM plan_strategies WHERE id = #{id}")
    PlanStrategy findById(@Param("id") Long id);

    @Select("SELECT * FROM plan_strategies WHERE plan_id = #{planId} LIMIT 1")
    PlanStrategy findByPlanId(@Param("planId") Long planId);

    @Insert("INSERT INTO plan_strategies(plan_id, blacklist_apps, whitelist_apps, auto_dnd, notify_silence, version) "
            + "SELECT #{planId}, #{blacklistApps}, #{whitelistApps}, #{autoDnd}, #{notifySilence}, #{version} "
            + "FROM plans WHERE id = #{planId} FOR SHARE")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PlanStrategy strategy);

    @Update("UPDATE plan_strategies SET blacklist_apps = #{blacklistApps}, whitelist_apps = #{whitelistApps}, "
            + "auto_dnd = #{autoDnd}, notify_silence = #{notifySilence}, version = version + 1 "
            + "WHERE plan_id = #{planId} AND version = #{version}")
    int updateWithVersion(PlanStrategy strategy);
}
