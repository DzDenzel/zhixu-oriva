package com.planlock.record.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.entity.JournalEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesContentMoodAndTagsAndRejectsRawAndNormalizedBoundaries() {
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
        assertThatThrownBy(() -> JournalRules.normalizeContent("a".repeat(20_001)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeContent("bad\u0007text"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeMood("x".repeat(25)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeMood("x".repeat(65)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.normalizeTags(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")))
                .isInstanceOf(BusinessException.class);

        List<String> tooManyDuplicates = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            tooManyDuplicates.add("dup");
        }
        assertThatThrownBy(() -> JournalRules.normalizeTags(tooManyDuplicates))
                .isInstanceOf(BusinessException.class);

        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> JournalRules.normalizeTags(withNull))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> JournalRules.normalizeTags(List.of("t".repeat(21))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JournalRules.validateClientId("bad\u0001id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void exportMarkdownIsolatesComplexSyntaxInsideDynamicFencesAndPreservesHeadingTree() {
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

        JournalEntry tricky = new JournalEntry();
        tricky.setId(3L);
        tricky.setEntryDate(LocalDate.of(2026, 11, 25));
        tricky.setMood("=CMD() #evil");
        tricky.setTags("[\"+formula\", \"tag|pipe\", \"![x](https://evil)\"]");
        tricky.setContent(
                "## 伪造日期标题\n"
                        + "Setext伪造\n"
                        + "===\n"
                        + "```\n"
                        + "闭合尝试\n"
                        + "````\n"
                        + "~~~bash\n"
                        + "> ## 引用内标题\n"
                        + "- ## 列表内标题\n"
                        + "inline <img src=x onerror=alert(1)>\n"
                        + "![tracker](https://example.invalid/px.png)\n"
                        + "=SUM(A1:A2)\r\n"
        );
        tricky.setStatus(1);

        String md = JournalRules.exportMarkdown(
                1042L,
                List.of(older, deleted, tricky),
                objectMapper,
                LocalDateTime.of(2026, 11, 26, 9, 30, 0)
        );

        assertThat(md).contains("# 知序 · 日常记录导出");
        assertThat(md).contains("u***42");
        assertThat(md).doesNotContain("1042");
        assertThat(md).doesNotContain("不应导出");
        assertThat(md.indexOf("## 2026-11-25")).isLessThan(md.indexOf("## 2026-11-24"));
        // Body contains 4 backticks, so outer fence must use 5 backticks.
        assertThat(md).contains("`````text\n");
        assertThat(md).contains("\n`````\n");
        // Verify top-level headings outside fences are only the main title and the two entries.
        List<String> outerHeadings = extractOuterHeadings(md);
        assertThat(outerHeadings).containsExactly(
                "# 知序 · 日常记录导出",
                "## 2026-11-25 · \\=CMD\\(\\) \\#evil",
                "## 2026-11-24 · 平静"
        );
    }

    private static List<String> extractOuterHeadings(String md) {
        List<String> headings = new ArrayList<>();
        String activeFence = null;
        for (String line : md.split("\n", -1)) {
            if (activeFence == null) {
                if (line.startsWith("```")) {
                    int n = 0;
                    while (n < line.length() && line.charAt(n) == '`') n++;
                    activeFence = "`".repeat(n);
                } else if (line.startsWith("#")) {
                    headings.add(line);
                }
            } else if (line.equals(activeFence)) {
                activeFence = null;
            }
        }
        return headings;
    }
}
