package com.planlock.account.mapper;

import com.planlock.account.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * users 表 Mapper。
 */
@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(@Param("id") Long id);

    @Select("SELECT * FROM users WHERE phone = #{account} OR email = #{account} LIMIT 1")
    User findByAccount(@Param("account") String account);

    @Insert("INSERT INTO users(phone, email, password_hash, backup_password_hash, security_question, security_answer_hash) "
            + "VALUES(#{phone}, #{email}, #{passwordHash}, #{backupPasswordHash}, #{securityQuestion}, #{securityAnswerHash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE users SET backup_password_hash = #{hash}, security_question = #{question}, "
            + "security_answer_hash = #{answerHash} WHERE id = #{id}")
    int updateBackupPassword(@Param("id") Long id,
                             @Param("hash") String hash,
                             @Param("question") String question,
                             @Param("answerHash") String answerHash);
}
