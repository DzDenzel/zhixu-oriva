package com.planlock.record.mapper;

import com.planlock.record.entity.ViolationRecord;
import com.planlock.record.dto.ViolationAppStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行期其他应用使用区间 Mapper（保留 violation_records 表名兼容 v1）。
 */
@Mapper
public interface ViolationRecordMapper {

    @Insert("INSERT INTO violation_records(plan_id, device_id, record_at, app_name, package_name, "
            + "started_at, ended_at, duration_ms, source_level, client_id) "
            + "VALUES(#{planId}, #{deviceId}, #{recordAt}, #{appName}, #{packageName}, "
            + "#{startedAt}, #{endedAt}, #{durationMs}, #{sourceLevel}, #{clientId})")
    int insert(ViolationRecord record);

    @Select("SELECT v.* FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.record_at BETWEEN #{start} AND #{end} ORDER BY v.record_at DESC")
    List<ViolationRecord> findByUserBetween(@Param("userId") Long userId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Select("SELECT v.* FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} ORDER BY v.id DESC")
    List<ViolationRecord> findAllByUser(@Param("userId") Long userId);

    @Select("SELECT COALESCE(NULLIF(v.package_name, ''), 'unknown') AS packageName, "
            + "SUBSTRING_INDEX(GROUP_CONCAT(COALESCE(v.app_name, '') ORDER BY v.started_at DESC SEPARATOR '|||'), '|||', 1) AS appName, "
            + "COUNT(*) AS count, COALESCE(SUM(v.duration_ms), 0) AS totalDurationMs, "
            + "COALESCE(SUM(CASE WHEN v.source_level = 'USAGE_STATS_APPROXIMATE' THEN v.duration_ms ELSE 0 END), 0) AS approxDurationMs "
            + "FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.started_at >= #{start} AND v.started_at < #{end} "
            + "AND v.ended_at IS NOT NULL AND v.duration_ms IS NOT NULL "
            + "GROUP BY COALESCE(NULLIF(v.package_name, ''), 'unknown') "
            + "ORDER BY totalDurationMs DESC, packageName ASC")
    List<ViolationAppStat> aggregateClosedV2ByAppBetween(@Param("userId") Long userId,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.started_at >= #{start} AND v.started_at < #{end} "
            + "AND v.ended_at IS NOT NULL AND v.duration_ms IS NOT NULL")
    int countClosedV2Between(@Param("userId") Long userId,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    @Select("SELECT COALESCE(SUM(v.duration_ms), 0) FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.started_at >= #{start} AND v.started_at < #{end} "
            + "AND v.ended_at IS NOT NULL AND v.duration_ms IS NOT NULL")
    long sumClosedV2DurationBetween(@Param("userId") Long userId,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM violation_records v JOIN plans p ON v.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND v.started_at IS NULL "
            + "AND v.record_at >= #{start} AND v.record_at < #{end}")
    int countLegacyBetween(@Param("userId") Long userId,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end);
}
