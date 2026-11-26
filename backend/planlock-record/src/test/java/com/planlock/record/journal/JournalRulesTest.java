package com.planlock.record.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.entity.JournalEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesContentMoodAndTagsAndRejectsBoundaries() {
        assertThat(JournalRules.normalizeContent("line1\r\nline2\rline3")).isEqualTo("line1\nline2\nline3");
        assertThat(JournalRules.normalizeMood("  平静  ")).isEqualTo("平静");
        assertThat(JournalRules.normalizeMood("   ")).isNull();
        assertThat(JournalRules.normalizeTags(List.of(" 工作 ", "工作", "", "复盘")))
                .containsExactly("工作", "复盘");

        assertThatThrownBy(() -> JournalRules.normalizeContent("  \n  "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAM.getCode());
        assertThatThrownBy(() -> JournalRules.normalizeContent("a".repeat(10_001)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeContent("bad\u0007text"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeMood("x".repeat(25)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeTags(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeTags(List.of("t".repeat(21))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.validateClientId("bad\u0001id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void exportMarkdownOrdersDescendingMasksUserAndEscapesDangerousSequences() {
        JournalEntry older = new JournalEntry();
        older.setId(1L);
        older.setEntryDate(LocalDate.of(2026, 11, 24));
        older.setMood("平静");
        older.setTags("[\"散步\"]");
        older.setContent("正常内容");
        older.setStatus(1);

        JournalEntry deleted = new JournalEntry();
        deleted.setId(2L);
        deleted.setEntryDate(LocalDate.of(2026, 11, 26));
        deleted.setContent("不应导出");
        deleted.setStatus(0);

        JournalEntry newer = new JournalEntry();
        newer.setId(3L);
        newer.setEntryDate(LocalDate.of(2026, 11, 25));
        newer.setMood("=CMD() #evil");
        newer.setTags("[\"+formula\", \"tag|pipe\"]");
        newer.setContent("## 伪造日期标题\n=SUM(A1:A2)\n<script>alert(1)</script>\n---");
        newer.setStatus(1);

        String md = JournalRules.exportMarkdown(
                1042L,
                List.of(older, deleted, newer),
                objectMapper,
                LocalDateTime.of(2026, 11, 26, 9, 30, 0)
        );

        assertThat(md).contains("# 知序 · 日常记录导出");
        assertThat(md).contains("u***42");
        assertThat(md).doesNotContain("1042");
        assertThat(md).doesNotContain("不应导出");
        assertThat(md.indexOf("## 2026-11-25")).isLessThan(md.indexOf("## 2026-11-24"));
        assertThat(md).contains("\\## 伪造日期标题");
        assertThat(md).contains("\\=SUM(A1:A2)");
        assertThat(md).contains("\\<script>alert(1)</script>");
        assertThat(md).contains("\\---");
        assertThat(md).contains("\\=CMD() \\#evil");
        assertThat(md).contains("\\+formula");
        assertThat(md).contains("tag\\|pipe");
    }
}
