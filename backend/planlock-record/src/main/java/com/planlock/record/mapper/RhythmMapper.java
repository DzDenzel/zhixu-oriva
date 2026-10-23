package com.planlock.record.mapper;

import com.planlock.record.dto.RhythmInputs.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Every history query joins plans to enforce the authenticated owner boundary. */
@Mapper
public interface RhythmMapper {
    @Select("SELECT id, name, type, repeat_rule AS repeatRule, status, status_log AS statusLog FROM plans WHERE user_id = #{userId}")
    List<Plan> plans(@Param("userId") Long userId);

    @Select("SELECT e.id, e.plan_id AS planId, e.plan_date AS planDate, e.status, e.started_at AS startedAt, e.ended_at AS endedAt, e.client_id AS clientId " +
            "FROM execution_records e JOIN plans p ON p.id = e.plan_id WHERE p.user_id = #{userId} ORDER BY e.id")
    List<Execution> executions(@Param("userId") Long userId);

    @Select("SELECT v.id, v.client_id AS clientId, v.app_name AS appName, v.package_name AS packageName, v.started_at AS startedAt, v.ended_at AS endedAt, v.duration_ms AS durationMs " +
            "FROM violation_records v JOIN plans p ON p.id = v.plan_id WHERE p.user_id = #{userId} AND v.started_at >= #{start} AND v.started_at < #{end} ORDER BY v.id")
    List<Behavior> behavior(@Param("userId") Long userId, @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);
}
