package com.planlock.record.journal.mapper;

import com.planlock.record.journal.entity.JournalEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * journal_entries 表 Mapper（全部单行读/写 SQL 强制绑定 userId）。
 */
@Mapper
public interface JournalMapper {

    @Select("SELECT * FROM journal_entries WHERE id = #{id} AND user_id = #{userId}")
    JournalEntry findById(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT * FROM journal_entries WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    JournalEntry findByIdForUpdate(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT id, user_id, status, version FROM journal_entries WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    JournalEntry findStateForUpdate(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT * FROM journal_entries WHERE user_id = #{userId} AND status = 1 "
            + "AND (#{from} IS NULL OR entry_date >= #{from}) "
            + "AND (#{to} IS NULL OR entry_date <= #{to}) "
            + "AND (#{mood} IS NULL OR mood = #{mood}) "
            + "ORDER BY entry_date DESC, id DESC")
    List<JournalEntry> findByUserIdFiltered(@Param("userId") Long userId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to,
                                            @Param("mood") String mood);

    @Select("SELECT * FROM journal_entries WHERE user_id = #{userId} AND status = 1 ORDER BY entry_date DESC, id DESC")
    List<JournalEntry> findAllActiveByUserId(@Param("userId") Long userId);

    @Insert("INSERT INTO journal_entries(user_id, client_id, entry_date, mood, tags, content, status, version) "
            + "VALUES(#{userId}, #{clientId}, #{entryDate}, #{mood}, #{tags}, #{content}, #{status}, #{version}) "
            + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JournalEntry entry);

    @Update("UPDATE journal_entries SET entry_date = #{entryDate}, mood = #{mood}, tags = #{tags}, content = #{content}, "
            + "version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND version = #{version}")
    int updateWithVersion(JournalEntry entry);

    @Update("UPDATE journal_entries SET status = 0, version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 1")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE journal_entries SET status = 0, version = version + 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 1 AND version = #{baseVersion}")
    int softDeleteWithVersion(@Param("id") Long id, @Param("userId") Long userId, @Param("baseVersion") Long baseVersion);
}
