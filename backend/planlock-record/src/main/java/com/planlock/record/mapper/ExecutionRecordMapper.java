package com.planlock.record.mapper;

import com.planlock.record.entity.ExecutionRecord;
import com.planlock.record.dto.PlanExecutionStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * execution_records 表 Mapper（record 模块）。
 */
@Mapper
public interface ExecutionRecordMapper {

    @Insert("INSERT INTO execution_records(plan_id, device_id, plan_date, status, started_at, ended_at, client_id) "
            + "VALUES(#{planId}, #{deviceId}, #{planDate}, #{status}, #{startedAt}, #{endedAt}, #{clientId})")
    int insert(ExecutionRecord record);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND e.plan_date = #{date} ORDER BY e.id DESC")
    List<ExecutionRecord> findByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND e.plan_date BETWEEN #{start} AND #{end} AND e.status = 1 "
            + "ORDER BY e.plan_date")
    List<ExecutionRecord> findCompletedBetween(@Param("userId") Long userId,
                                               @Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    @Select("SELECT e.* FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} ORDER BY e.id DESC")
    List<ExecutionRecord> findAllByUser(@Param("userId") Long userId);

    @Select("SELECT e.plan_id AS planId, "
            + "SUM(CASE WHEN e.status = 1 THEN 1 ELSE 0 END) AS completedCount, "
            + "SUM(CASE WHEN e.status = 2 THEN 1 ELSE 0 END) AS earlyEndedCount, "
            + "COALESCE(SUM(CASE WHEN e.status IN (1, 2) AND e.started_at IS NOT NULL AND e.ended_at IS NOT NULL "
            + "THEN TIMESTAMPDIFF(MICROSECOND, e.started_at, e.ended_at) / 1000 ELSE 0 END), 0) AS actualDurationMs "
            + "FROM execution_records e JOIN plans p ON e.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND e.plan_date BETWEEN #{start} AND #{end} "
            + "AND e.status IN (1, 2) GROUP BY e.plan_id ORDER BY e.plan_id")
    List<PlanExecutionStat> aggregateByPlanBetween(@Param("userId") Long userId,
                                                   @Param("start") LocalDate start,
                                                   @Param("end") LocalDate end);
}
