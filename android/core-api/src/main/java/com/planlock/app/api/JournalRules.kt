package com.planlock.app.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JournalExportItem(
    val id: Long,
    val entryDate: String,
    val mood: String?,
    val tags: List<String>,
    val content: String,
)

/**
 * 日常记录跨端一致性校验、标签清洗与 Markdown 安全导出规则（对齐 backend JournalRules）。
 */
object JournalRules {
    const val MAX_CLIENT_ID_LENGTH = 64
    const val MAX_MOOD_LENGTH = 24
    const val MAX_TAGS_COUNT = 8
    const val MAX_TAG_LENGTH = 20
    const val MAX_CONTENT_LENGTH = 10_000

    val PRESET_MOODS: List<String> = listOf("平静", "开心", "专注", "疲惫", "焦虑", "充实")

    private val ISO_DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val HEADING_LINE = Regex("^(\\s{0,3})(#{1,6})(\\s|$)(.*)$")
    private val THEMATIC_BREAK = Regex("^(\\s{0,3})([-*_])\\2{2,}\\s*$")
    private val HTML_TAG_START = Regex("^(\\s*)(<[a-zA-Z/!].*)$")

    fun isValidIsoDate(value: String): Boolean {
        if (!ISO_DATE_REGEX.matches(value)) return false
        val parts = value.split('-')
        val year = parts[0].toIntOrNull() ?: return false
        val month = parts[1].toIntOrNull() ?: return false
        val day = parts[2].toIntOrNull() ?: return false
        if (month !in 1..12 || day !in 1..31) return false
        val maxDay = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDay
    }

    fun normalizeMood(mood: String?): String? {
        if (mood == null) return null
        require(!containsDisallowedControl(mood, allowNewlineAndTab = false)) { "心情不能包含控制字符" }
        val trimmed = mood.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.length <= MAX_MOOD_LENGTH) { "心情长度不能超过 24 字符" }
        return trimmed
    }

    fun normalizeContent(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        require(!containsDisallowedControl(normalized, allowNewlineAndTab = true)) { "正文包含非法控制字符" }
        require(normalized.isNotBlank()) { "正文不能为空" }
        require(normalized.length <= MAX_CONTENT_LENGTH) { "正文长度不能超过 10000 字符" }
        return normalized
    }

    fun normalizeTags(rawTags: List<String>): List<String> {
        if (rawTags.isEmpty()) return emptyList()
        val cleaned = LinkedHashSet<String>()
        for (raw in rawTags) {
            require(!containsDisallowedControl(raw, allowNewlineAndTab = false)) { "标签不能包含控制字符" }
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            require(trimmed.length <= MAX_TAG_LENGTH) { "单个标签不能超过 20 字符" }
            cleaned += trimmed
            require(cleaned.size <= MAX_TAGS_COUNT) { "标签最多保留 8 个" }
        }
        return cleaned.toList()
    }

    fun parseTagsJson(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun encodeTagsJson(tags: List<String>): String? {
        val cleaned = normalizeTags(tags)
        return if (cleaned.isEmpty()) null else Json.encodeToString(cleaned)
    }

    fun exportMarkdown(
        entries: List<JournalExportItem>,
        scopeLabel: String = "本机导出",
        exportedAt: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
    ): String {
        val sorted = entries.sortedWith(
            compareByDescending<JournalExportItem> { it.entryDate }
                .thenByDescending { it.id },
        )
        return buildString {
            append("# 知序 · 日常记录导出\n\n")
            append("- 来源: ").append(escapeInline(scopeLabel)).append('\n')
            append("- 导出时间: ").append(escapeInline(exportedAt)).append('\n')
            append("- 记录数: ").append(sorted.size).append("\n\n")
            for (entry in sorted) {
                append("## ").append(entry.entryDate)
                val mood = entry.mood?.trim()?.takeIf { it.isNotEmpty() }
                if (mood != null) {
                    append(" · ").append(escapeInline(mood))
                }
                append("\n\n")
                if (mood != null) {
                    append("- 心情: ").append(escapeInline(mood)).append('\n')
                }
                if (entry.tags.isNotEmpty()) {
                    append("- 标签: ")
                    append(entry.tags.joinToString(", ") { escapeInline(it) })
                    append('\n')
                }
                if (mood != null || entry.tags.isNotEmpty()) {
                    append('\n')
                }
                append(escapeBody(entry.content)).append("\n\n")
            }
        }
    }

    fun escapeInline(value: String): String {
        val sanitized = stripControlChars(value, allowNewlineAndTab = false).trim()
        if (sanitized.isEmpty()) return ""
        return buildString(sanitized.length + 8) {
            sanitized.forEachIndexed { index, c ->
                if (index == 0 && (c == '=' || c == '+' || c == '-' || c == '@')) {
                    append('\\')
                } else if (c == '\\' || c == '`' || c == '*' || c == '_' || c == '#' ||
                    c == '>' || c == '|' || c == '[' || c == ']' || c == '<'
                ) {
                    append('\\')
                }
                append(c)
            }
        }
    }

    fun escapeBody(raw: String): String {
        val normalized = stripControlChars(raw.replace("\r\n", "\n").replace('\r', '\n'), allowNewlineAndTab = true)
        return normalized.split('\n').joinToString("\n") { line ->
            val heading = HEADING_LINE.matchEntire(line)
            val html = HTML_TAG_START.matchEntire(line)
            when {
                heading != null -> "${heading.groupValues[1]}\\${heading.groupValues[2]}${heading.groupValues[3]}${heading.groupValues[4]}"
                THEMATIC_BREAK.matches(line) -> "\\$line"
                html != null -> "${html.groupValues[1]}\\${html.groupValues[2]}"
                line.isNotEmpty() && line[0] in charArrayOf('=', '+', '@') -> "\\$line"
                else -> line
            }
        }
    }

    private fun containsDisallowedControl(value: String, allowNewlineAndTab: Boolean): Boolean =
        value.any { c ->
            !(allowNewlineAndTab && (c == '\n' || c == '\t')) &&
                (c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F)
        }

    private fun stripControlChars(value: String, allowNewlineAndTab: Boolean): String =
        buildString(value.length) {
            for (c in value) {
                if ((allowNewlineAndTab && (c == '\n' || c == '\t')) ||
                    (c.code >= 0x20 && c.code != 0x7F && c.code !in 0x80..0x9F)
                ) {
                    append(c)
                }
            }
        }
}
