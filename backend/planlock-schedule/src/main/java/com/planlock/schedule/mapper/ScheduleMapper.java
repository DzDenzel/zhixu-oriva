package com.planlock.schedule.mapper;

import com.planlock.schedule.entity.Schedule;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ScheduleMapper {
    @Select("SELECT * FROM schedules WHERE id=#{id}") Schedule findById(@Param("id") Long id);
    @Select("SELECT * FROM schedules WHERE id=#{id} FOR UPDATE") Schedule findByIdForUpdate(@Param("id") Long id);
    @Select("SELECT * FROM schedules WHERE user_id=#{userId} ORDER BY start_date,start_time,id") List<Schedule> findByUserId(@Param("userId") Long userId);
    @Insert("INSERT INTO schedules(user_id,client_id,title,start_date,start_time,end_date,end_time,all_day,location,note,remind_advance_min,repeat_rule,linked_plan_id,tz_id,status,version) VALUES(#{userId},#{clientId},#{title},#{startDate},#{startTime},#{endDate},#{endTime},#{allDay},#{location},#{note},#{remindAdvanceMin},#{repeatRule},#{linkedPlanId},#{tzId},#{status},#{version}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(Schedule schedule);
    @Update("UPDATE schedules SET title=#{title},start_date=#{startDate},start_time=#{startTime},end_date=#{endDate},end_time=#{endTime},all_day=#{allDay},location=#{location},note=#{note},remind_advance_min=#{remindAdvanceMin},repeat_rule=#{repeatRule},linked_plan_id=#{linkedPlanId},tz_id=#{tzId},version=version+1 WHERE id=#{id} AND user_id=#{userId} AND version=#{version}") int updateWithVersion(Schedule schedule);
    @Delete("DELETE FROM schedules WHERE id=#{id} AND user_id=#{userId}") int delete(@Param("id") Long id,@Param("userId") Long userId);
    @Delete("DELETE FROM schedules WHERE id=#{id} AND user_id=#{userId} AND version=#{baseVersion}") int deleteWithVersion(@Param("id") Long id,@Param("userId") Long userId,@Param("baseVersion") Long baseVersion);
}
