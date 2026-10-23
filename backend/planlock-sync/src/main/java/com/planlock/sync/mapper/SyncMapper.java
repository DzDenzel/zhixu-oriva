package com.planlock.sync.mapper;

import com.planlock.sync.dto.SyncPlanView;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.dto.SyncStrategyView;
import com.planlock.sync.dto.SyncScheduleView;
import com.planlock.sync.dto.SyncItemResult;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 同步专用 Mapper（sync 模块）。同步是跨模块聚合与写入操作，见 04-技术实现/实现记录。
 */
@Mapper
public interface SyncMapper {

    @Select("SELECT type_key,user_id,name,color,version,deleted FROM plan_types WHERE user_id=#{userId} ORDER BY type_key")
    List<com.planlock.sync.dto.SyncPlanTypeView> pullPlanTypes(@Param("userId") Long userId);

    // Deletion wins permanently. Replayed mutations are idempotent; later names/colors win.
    @Insert("INSERT INTO plan_types(user_id,type_key,name,color,version,deleted,mutation_id) VALUES(#{userId},#{t.typeKey},#{t.name},#{t.color},0,#{t.deleted},#{t.mutationId}) "
            + "ON DUPLICATE KEY UPDATE version=IF(mutation_id=VALUES(mutation_id),version,version+1), "
            + "name=IF(deleted=1 OR mutation_id=VALUES(mutation_id),name,VALUES(name)),color=IF(deleted=1 OR mutation_id=VALUES(mutation_id),color,VALUES(color)),deleted=IF(mutation_id=VALUES(mutation_id),deleted,GREATEST(deleted,VALUES(deleted))),mutation_id=VALUES(mutation_id)")
    int upsertPlanType(@Param("userId") Long userId, @Param("t") SyncPushRequest.PlanTypeItem item);

    @Select("SELECT version FROM plan_types WHERE user_id=#{userId} AND type_key=#{key} FOR UPDATE")
    Long planTypeVersion(@Param("userId") Long userId, @Param("key") String key);

    @Update("UPDATE plans p JOIN plan_types t ON p.user_id=t.user_id AND p.type=t.type_key SET p.type='其他',p.version=p.version+1 WHERE t.user_id=#{userId} AND t.type_key=#{key} AND t.deleted=1")
    int reassignDeletedPlanType(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT deleted FROM plan_types WHERE user_id=#{userId} AND type_key=#{key} FOR SHARE")
    Integer isDeletedPlanType(@Param("userId") Long userId, @Param("key") String key);

    // ---- pull ----

    @Select("SELECT id, client_id, user_id, name, type, start_time, end_time, repeat_rule, color, icon, "
            + "remind_advance_min, remind_methods, status, version, ended_occurrence_date, status_log, updated_at "
            + "FROM plans WHERE user_id = #{userId} ORDER BY id")
    List<SyncPlanView> pullPlans(@Param("userId") Long userId);

    @Select("SELECT id,client_id,user_id,title,start_date,start_time,end_date,end_time,all_day,location,location_lat,location_lon,note,remind_advance_min,repeat_rule,linked_plan_id,tz_id,status,version,created_at,updated_at FROM schedules WHERE user_id=#{userId} ORDER BY start_date,start_time,id")
    List<SyncScheduleView> pullSchedules(@Param("userId") Long userId);

    @Select("SELECT s.id, s.plan_id, s.blacklist_apps, s.whitelist_apps, s.auto_dnd, s.notify_silence, s.version, s.updated_at "
            + "FROM plan_strategies s JOIN plans p ON s.plan_id = p.id WHERE p.user_id = #{userId}")
    List<SyncStrategyView> pullStrategies(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM execution_records e JOIN plans p ON e.plan_id = p.id WHERE p.user_id = #{userId}")
    int countExecutions(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM violation_records v JOIN plans p ON v.plan_id = p.id WHERE p.user_id = #{userId}")
    int countViolations(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM unlock_records u JOIN plans p ON u.plan_id = p.id WHERE p.user_id = #{userId}")
    int countUnlocks(@Param("userId") Long userId);

    // ---- push：计划 ----

    @Select("SELECT COUNT(*) FROM plans WHERE id = #{id} AND user_id = #{userId}")
    int countOwnedPlan(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("INSERT INTO plans(user_id, client_id, name, type, start_time, end_time, repeat_rule, color, icon, "
            + "remind_advance_min, remind_methods, status, version, ended_occurrence_date, status_log) "
            + "VALUES(#{userId}, #{p.clientId}, #{p.name}, #{p.type}, #{p.startTime}, #{p.endTime}, #{p.repeatRule}, "
            + "#{p.color}, #{p.icon}, #{p.remindAdvanceMin}, #{p.remindMethods}, #{p.status}, 0, #{p.endedOccurrenceDate}, #{p.statusLog}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "p.id")
    int insertPlan(@Param("userId") Long userId, @Param("p") SyncPushRequest.PlanItem p);

    @Update("UPDATE plans SET name = #{p.name}, type = #{p.type}, start_time = #{p.startTime}, "
            + "end_time = #{p.endTime}, repeat_rule = #{p.repeatRule}, color = #{p.color}, icon = #{p.icon}, "
            + "remind_advance_min = #{p.remindAdvanceMin}, remind_methods = #{p.remindMethods}, "
            + "status = #{p.status}, ended_occurrence_date = COALESCE(#{p.endedOccurrenceDate}, ended_occurrence_date), status_log = COALESCE(#{p.statusLog}, status_log), version = version + 1 "
            + "WHERE id = #{p.id} AND user_id = #{userId} AND version = #{p.baseVersion}")
    int updatePlan(@Param("userId") Long userId, @Param("p") SyncPushRequest.PlanItem p);

    @Select("SELECT COUNT(*) FROM schedules WHERE id=#{id} AND user_id=#{userId}")
    int countOwnedSchedule(@Param("id") Long id,@Param("userId") Long userId);

    @Insert("INSERT INTO schedules(user_id,client_id,title,start_date,start_time,end_date,end_time,all_day,location,location_lat,location_lon,note,remind_advance_min,repeat_rule,linked_plan_id,tz_id,status,version) VALUES(#{userId},#{s.clientId},#{s.title},#{s.startDate},#{s.startTime},#{s.endDate},#{s.endTime},#{s.allDay},#{s.location},#{s.locationLat},#{s.locationLon},#{s.note},#{s.remindAdvanceMin},#{s.repeatRule},#{s.linkedPlanId},#{s.tzId},#{s.status},0) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "s.id")
    int insertSchedule(@Param("userId") Long userId,@Param("s") SyncPushRequest.ScheduleItem s);

    @Update("UPDATE schedules SET title=#{s.title},start_date=#{s.startDate},start_time=#{s.startTime},end_date=#{s.endDate},end_time=#{s.endTime},all_day=#{s.allDay},location=#{s.location},location_lat=#{s.locationLat},location_lon=#{s.locationLon},note=#{s.note},remind_advance_min=#{s.remindAdvanceMin},repeat_rule=#{s.repeatRule},linked_plan_id=#{s.linkedPlanId},tz_id=#{s.tzId},status=#{s.status},version=version+1 WHERE id=#{s.id} AND user_id=#{userId} AND version=#{s.baseVersion}")
    int updateSchedule(@Param("userId") Long userId,@Param("s") SyncPushRequest.ScheduleItem s);

    // Locking reads avoid stale REPEATABLE READ snapshots after a concurrent key winner.
    @Select("SELECT id,version,client_id FROM plans WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    SyncItemResult planIdentity(@Param("userId") Long userId,@Param("id") Long id);

    @Select("SELECT id,version,client_id FROM schedules WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    SyncItemResult scheduleIdentity(@Param("userId") Long userId,@Param("id") Long id);

    // Ownership is checked through p, but only strategy rows need identity-read locks.
    @Select("SELECT s.id,s.version FROM plan_strategies s JOIN plans p ON p.id=s.plan_id WHERE s.plan_id=#{planId} AND p.user_id=#{userId} FOR UPDATE OF s")
    SyncItemResult strategyIdentity(@Param("userId") Long userId,@Param("planId") Long planId);

    // ---- push：策略 ----

    @Select("SELECT COUNT(*) FROM plan_strategies WHERE plan_id = #{planId}")
    int countStrategy(@Param("planId") Long planId);

    @Insert("INSERT INTO plan_strategies(plan_id, blacklist_apps, whitelist_apps, auto_dnd, notify_silence, version) "
            + "SELECT #{s.planId}, #{s.blacklistApps}, #{s.whitelistApps}, #{s.autoDnd}, #{s.notifySilence}, 0 "
            + "FROM plans WHERE id = #{s.planId} FOR SHARE")
    int insertStrategy(@Param("s") SyncPushRequest.StrategyItem s);

    @Update("UPDATE plan_strategies SET blacklist_apps = #{s.blacklistApps}, whitelist_apps = #{s.whitelistApps}, "
            + "auto_dnd = #{s.autoDnd}, notify_silence = #{s.notifySilence}, version = version + 1 "
            + "WHERE plan_id = #{s.planId} AND version = #{s.baseVersion}")
    int updateStrategy(@Param("s") SyncPushRequest.StrategyItem s);
}
