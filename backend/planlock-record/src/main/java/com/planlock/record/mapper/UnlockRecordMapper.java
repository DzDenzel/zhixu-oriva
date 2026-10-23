package com.planlock.record.mapper;

import com.planlock.record.entity.UnlockRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * unlock_records 表 Mapper（record 模块）。
 */
@Mapper
public interface UnlockRecordMapper {

    @Select("SELECT u.* FROM unlock_records u JOIN plans p ON u.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND u.id > #{afterId} ORDER BY u.id LIMIT #{limit}")
    List<UnlockRecord> findHistoryAfter(@Param("userId") Long userId,
                                      @Param("afterId") long afterId, @Param("limit") int limit);

    @Insert("INSERT INTO unlock_records(plan_id, device_id, unlock_at, method, client_id) "
            + "VALUES(#{planId}, #{deviceId}, #{unlockAt}, #{method}, #{clientId})")
    int insert(UnlockRecord record);

    @Select("SELECT u.* FROM unlock_records u JOIN plans p ON u.plan_id = p.id "
            + "WHERE p.user_id = #{userId} AND u.unlock_at BETWEEN #{start} AND #{end} ORDER BY u.unlock_at DESC")
    List<UnlockRecord> findByUserBetween(@Param("userId") Long userId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    @Select("SELECT u.* FROM unlock_records u JOIN plans p ON u.plan_id = p.id "
            + "WHERE p.user_id = #{userId} ORDER BY u.id DESC")
    List<UnlockRecord> findAllByUser(@Param("userId") Long userId);
}
