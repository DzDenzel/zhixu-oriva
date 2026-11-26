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
    const val MAX_RAW_MOOD_LENGTH = 64
    const val MAX_TAGS_COUNT = 8
    const val MAX_RAW_TAGS_COUNT = 32
    const val MAX_TAG_LENGTH = 20
    const val MAX_RAW_TAG_LENGTH = 64
    const val MAX_CONTENT_LENGTH = 10_000
    const val MAX_RAW_CONTENT_LENGTH = 20_000

    val PRESET_MOODS: List<String> = listOf("平静", "开心", "专注", "疲惫", "焦虑", "充实")

    private val ISO_DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

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
        require(mood.length <= MAX_RAW_MOOD_LENGTH) { "心情长度超出限制" }
        require(!containsDisallowedControl(mood, allowNewlineAndTab = false)) { "心情不能包含控制字符" }
        val trimmed = mood.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.length <= MAX_MOOD_LENGTH) { "心情长度不能超过 24 字符" }
        return trimmed
    }

    fun normalizeContent(raw: String): String {
        require(raw.length <= MAX_RAW_CONTENT_LENGTH) { "正文长度超出限制" }
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        require(!containsDisallowedControl(normalized, allowNewlineAndTab = true)) { "正文包含非法控制字符" }
        require(normalized.isNotBlank()) { "正文不能为空" }
        require(normalized.length <= MAX_CONTENT_LENGTH) { "正文长度不能超过 10000 字符" }
        return normalized
    }

    fun normalizeTags(rawTags: List<String>): List<String> {
        if (rawTags.isEmpty()) return emptyList()
        require(rawTags.size <= MAX_RAW_TAGS_COUNT) { "标签列表项数超出限制" }
        val cleaned = LinkedHashSet<String>()
        for (raw in rawTags) {
            require(raw.length <= MAX_RAW_TAG_LENGTH) { "单个标签长度超出限制" }
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
            compareByDescending<JournalExportItem> { if (isValidIsoDate(it.entryDate)) it.entryDate else "0000-00-00" }
                .thenByDescending { it.id },
        )
        return buildString {
            append("# 知序 · 日常记录导出\n\n")
            append("- 来源: ").append(escapeInline(scopeLabel)).append('\n')
            append("- 导出时间: ").append(escapeInline(exportedAt)).append('\n')
            append("- 记录数: ").append(sorted.size).append("\n\n")
            for (entry in sorted) {
                val safeDate = if (isValidIsoDate(entry.entryDate)) entry.entryDate else "0000-00-00"
                append("## ").append(safeDate)
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
                append(formatFencedBody(entry.content)).append("\n\n")
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
                    c == '>' || c == '|' || c == '[' || c == ']' || c == '<' ||
                    c == '(' || c == ')' || c == '!'
                ) {
                    append('\\')
                }
                append(c)
            }
        }
    }

    /**
     * 使用动态长度反引号代码围栏隔离任意多行正文，防止 Setext/ATX 标题、围栏闭合、引用或内联 HTML 破坏结构。
     */
    fun formatFencedBody(raw: String): String {
        val sanitized = stripControlChars(raw.replace("\r\n", "\n").replace('\r', '\n'), allowNewlineAndTab = true)
        var maxRun = 0
        var currentRun = 0
        for (c in sanitized) {
            if (c == '`') {
                currentRun++
                if (currentRun > maxRun) maxRun = currentRun
            } else {
                currentRun = 0
            }
        }
        val fence = "`".repeat(maxOf(3, maxRun + 1))
        return buildString(sanitized.length + fence.length * 2 + 8) {
            append(fence).append("text\n").append(sanitized)
            if (sanitized.isNotEmpty() && !sanitized.endsWith('\n')) {
                append('\n')
            }
            append(fence)
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
