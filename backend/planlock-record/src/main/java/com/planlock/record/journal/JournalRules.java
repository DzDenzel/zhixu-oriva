package com.planlock.record.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.journal.entity.JournalEntry;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 日常记录领域校验、标签清洗与安全 Markdown 导出规则。
 */
public final class JournalRules {

    public static final int MAX_CLIENT_ID_LENGTH = 64;
    public static final int MAX_MOOD_LENGTH = 24;
    public static final int MAX_TAGS_COUNT = 8;
    public static final int MAX_TAG_LENGTH = 20;
    public static final int MAX_CONTENT_LENGTH = 10_000;

    private static final Pattern HEADING_LINE = Pattern.compile("^(\\s{0,3})(#{1,6})(\\s|$)(.*)$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^(\\s{0,3})([-*_])\\2{2,}\\s*$");
    private static final Pattern HTML_TAG_START = Pattern.compile("^(\\s*)(<[a-zA-Z/!].*)$");
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JournalRules() {
    }

    public static void validateClientId(String clientId) {
        if (clientId == null) {
            return;
        }
        if (clientId.isBlank() || clientId.length() > MAX_CLIENT_ID_LENGTH || containsDisallowedControl(clientId, false)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "clientId 长度须为 1 至 64 且不含控制字符");
        }
    }

    public static String normalizeMood(String mood) {
        if (mood == null) {
            return null;
        }
        if (containsDisallowedControl(mood, false)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "心情不能包含控制字符");
        }
        String trimmed = mood.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_MOOD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "心情长度不能超过 24 字符");
        }
        return trimmed;
    }

    public static String normalizeContent(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "正文不能为空");
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (containsDisallowedControl(normalized, true)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "正文包含非法控制字符");
        }
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "正文不能为空");
        }
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "正文长度不能超过 10000 字符");
        }
        return normalized;
    }

    public static List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String tag : rawTags) {
            if (tag == null) {
                continue;
            }
            if (containsDisallowedControl(tag, false)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "标签不能包含控制字符");
            }
            String trimmed = tag.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "单个标签不能超过 20 字符");
            }
            cleaned.add(trimmed);
            if (cleaned.size() > MAX_TAGS_COUNT) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "标签最多保留 8 个");
            }
        }
        return List.copyOf(cleaned);
    }

    public static String serializeTags(List<String> normalizedTags, ObjectMapper objectMapper) {
        if (normalizedTags == null || normalizedTags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalizedTags);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "标签序列化失败");
        }
    }

    public static List<String> parseTags(String tagsJson, ObjectMapper objectMapper) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(tagsJson, new TypeReference<>() {});
            return parsed == null ? List.of() : parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "标签 JSON 解析失败");
        }
    }

    public static String exportMarkdown(Long userId, List<JournalEntry> entries, ObjectMapper objectMapper, LocalDateTime exportedAt) {
        List<JournalEntry> active = entries == null ? List.of() : entries.stream()
                .filter(e -> e.getStatus() == null || e.getStatus() == 1)
                .sorted(Comparator
                        .comparing(JournalEntry::getEntryDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(JournalEntry::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# 知序 · 日常记录导出\n\n");
        sb.append("- 用户标识: ").append(maskUserId(userId)).append('\n');
        sb.append("- 导出时间: ").append((exportedAt == null ? LocalDateTime.now() : exportedAt).format(EXPORT_TIME)).append('\n');
        sb.append("- 记录数: ").append(active.size()).append("\n\n");

        for (JournalEntry entry : active) {
            sb.append("## ").append(entry.getEntryDate());
            String mood = normalizeMoodOptional(entry.getMood());
            if (mood != null) {
                sb.append(" · ").append(escapeMarkdownInline(mood));
            }
            sb.append("\n\n");
            if (mood != null) {
                sb.append("- 心情: ").append(escapeMarkdownInline(mood)).append('\n');
            }
            List<String> tags = parseTags(entry.getTags(), objectMapper);
            if (!tags.isEmpty()) {
                String renderedTags = tags.stream()
                        .map(JournalRules::escapeMarkdownInline)
                        .collect(Collectors.joining(", "));
                sb.append("- 标签: ").append(renderedTags).append('\n');
            }
            if (mood != null || !tags.isEmpty()) {
                sb.append('\n');
            }
            sb.append(escapeMarkdownBody(entry.getContent())).append("\n\n");
        }
        return sb.toString();
    }

    static String maskUserId(Long userId) {
        if (userId == null) {
            return "u***";
        }
        long abs = Math.abs(userId);
        return "u***" + String.format("%02d", abs % 100);
    }

    static String escapeMarkdownInline(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = stripControlChars(value, false).trim();
        if (sanitized.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sanitized.length() + 8);
        for (int i = 0; i < sanitized.length(); i++) {
            char c = sanitized.charAt(i);
            if (i == 0 && (c == '=' || c == '+' || c == '-' || c == '@')) {
                out.append('\\');
            } else if (c == '\\' || c == '`' || c == '*' || c == '_' || c == '#' || c == '>'
                    || c == '|' || c == '[' || c == ']' || c == '<') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static String escapeMarkdownBody(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = stripControlChars(raw.replace("\r\n", "\n").replace('\r', '\n'), true);
        String[] lines = normalized.split("\n", -1);
        List<String> escaped = new ArrayList<>(lines.length);
        for (String line : lines) {
            String safeLine = line;
            Matcher heading = HEADING_LINE.matcher(safeLine);
            if (heading.matches()) {
                safeLine = heading.group(1) + "\\" + heading.group(2) + heading.group(3) + heading.group(4);
            } else if (THEMATIC_BREAK.matcher(safeLine).matches()) {
                safeLine = "\\" + safeLine;
            } else if (HTML_TAG_START.matcher(safeLine).matches()) {
                Matcher html = HTML_TAG_START.matcher(safeLine);
                if (html.matches()) {
                    safeLine = html.group(1) + "\\" + html.group(2);
                }
            } else if (!safeLine.isEmpty()) {
                char first = safeLine.charAt(0);
                if (first == '=' || first == '+' || first == '@') {
                    safeLine = "\\" + safeLine;
                }
            }
            escaped.add(safeLine);
        }
        return String.join("\n", escaped);
    }

    private static String normalizeMoodOptional(String mood) {
        if (mood == null || mood.isBlank()) {
            return null;
        }
        return mood.trim();
    }

    private static boolean containsDisallowedControl(String value, boolean allowNewlineAndTab) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (allowNewlineAndTab && (c == '\n' || c == '\t')) {
                continue;
            }
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                return true;
            }
        }
        return false;
    }

    private static String stripControlChars(String value, boolean allowNewlineAndTab) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (allowNewlineAndTab && (c == '\n' || c == '\t')) {
                sb.append(c);
            } else if (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c <= 0x9F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
