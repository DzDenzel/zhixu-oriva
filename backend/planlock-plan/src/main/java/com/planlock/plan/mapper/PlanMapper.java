package com.planlock.plan.mapper;

import com.planlock.plan.entity.Plan;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * plans 表 Mapper（plan 模块）。
 */
@Mapper
public interface PlanMapper {

    @Select("SELECT * FROM plans WHERE id = #{id}")
    Plan findById(@Param("id") Long id);

    @Select("SELECT * FROM plans WHERE id = #{id} FOR UPDATE")
    Plan findByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM plans WHERE user_id = #{userId} ORDER BY id DESC")
    List<Plan> findByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO plans(user_id, client_id, name, type, start_time, end_time, repeat_rule, color, icon, "
            + "remind_advance_min, remind_methods, status, version) "
            + "VALUES(#{userId}, #{clientId}, #{name}, #{type}, #{startTime}, #{endTime}, #{repeatRule}, #{color}, #{icon}, "
            + "#{remindAdvanceMin}, #{remindMethods}, #{status}, #{version}) "
            + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Plan plan);

    /**
     * 乐观锁更新：仅当 version 等于 baseVersion（plan.version）时更新并自增版本号。
     */
    @Update("UPDATE plans SET name = #{name}, type = #{type}, start_time = #{startTime}, end_time = #{endTime}, "
            + "repeat_rule = #{repeatRule}, color = #{color}, icon = #{icon}, "
            + "remind_advance_min = #{remindAdvanceMin}, remind_methods = #{remindMethods}, version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND version = #{version}")
    int updateWithVersion(Plan plan);

    @Update("UPDATE plans SET status = CASE WHEN status = 1 THEN 0 ELSE 1 END, version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId}")
    int togglePause(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM plans WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);
}
