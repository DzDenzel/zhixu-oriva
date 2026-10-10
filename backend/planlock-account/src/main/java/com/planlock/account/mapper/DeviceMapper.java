package com.planlock.account.mapper;

import com.planlock.account.entity.Device;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * devices 表 Mapper。
 */
@Mapper
public interface DeviceMapper {

    @Select("SELECT * FROM devices WHERE id = #{id}")
    Device findById(@Param("id") Long id);

    @Select("SELECT * FROM devices WHERE device_uid = #{deviceUid} LIMIT 1")
    Device findByDeviceUid(@Param("deviceUid") String deviceUid);

    @Select("SELECT * FROM devices WHERE user_id = #{userId}")
    List<Device> findByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO devices(user_id, device_name, platform, device_uid, is_primary, last_online_at) "
            + "VALUES(#{userId}, #{deviceName}, #{platform}, #{deviceUid}, #{isPrimary}, #{lastOnlineAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Device device);

    @Update("UPDATE devices SET user_id = #{userId}, device_name = #{deviceName}, platform = #{platform}, "
            + "is_primary = #{isPrimary}, last_online_at = #{lastOnlineAt} WHERE id = #{id}")
    int update(Device device);

    @Update("UPDATE devices SET last_online_at = #{at} WHERE id = #{id}")
    int touch(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Update("UPDATE devices SET is_primary = 0 WHERE user_id = #{userId}")
    int clearPrimary(@Param("userId") Long userId);

    @Update("UPDATE devices SET is_primary = 1 WHERE id = #{id}")
    int setPrimary(@Param("id") Long id);

    @Delete("DELETE FROM devices WHERE id = #{id}")
    int delete(@Param("id") Long id);
}
