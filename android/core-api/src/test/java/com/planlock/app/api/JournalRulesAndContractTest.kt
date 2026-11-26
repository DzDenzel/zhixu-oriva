package com.planlock.app.api

import com.planlock.app.api.dto.JournalDto
import com.planlock.app.api.dto.JournalUpsertRequest
import com.planlock.app.api.dto.SyncItemResult
import com.planlock.app.api.dto.SyncJournalItem
import com.planlock.app.api.dto.SyncJournalView
import com.planlock.app.api.dto.SyncPullResponse
import com.planlock.app.api.dto.SyncPushRequest
import com.planlock.app.api.mock.MockApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalRulesAndContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `normalization enforces raw and normalized content mood and tag boundaries`() {
        assertEquals("a\nb\nc", JournalRules.normalizeContent("a\r\nb\rc"))
        assertEquals("平静", JournalRules.normalizeMood("  平静  "))
        assertNull(JournalRules.normalizeMood("   "))
        assertEquals(listOf("工作", "复盘"), JournalRules.normalizeTags(listOf(" 工作 ", "工作", "", "复盘")))
        assertTrue(JournalRules.isValidIsoDate("2026-11-26"))
        assertFalse(JournalRules.isValidIsoDate("2026-02-30"))

        try { JournalRules.normalizeContent("   "); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeContent("x".repeat(10_001)); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeContent("x".repeat(20_001)); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeContent("bad\u0007"); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeMood("a".repeat(25)); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeMood("a".repeat(65)); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeTags((1..9).map { "t$it" }); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeTags(List(33) { "dup" }); fail() } catch (_: IllegalArgumentException) {}
        try { JournalRules.normalizeTags(listOf("t".repeat(21))); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `exportMarkdown isolates complex markdown syntax and sanitizes invalid dates`() {
        val md = JournalRules.exportMarkdown(
            entries = listOf(
                JournalExportItem(1L, "2026-11-24", "平静", listOf("散步"), "旧记录"),
                JournalExportItem(
                    2L,
                    "2026-11-25",
                    "=CMD() #evil",
                    listOf("+formula", "tag|pipe", "![x](https://evil)"),
                    "## 伪造日期标题\nSetext伪造\n===\n```\n闭合尝试\n````\n~~~bash\n> ## 引用\n- ## 列表\ninline <img src=x>\n![px](https://example.invalid)\n=SUM(1,2)\r\n",
                ),
                JournalExportItem(3L, "2026-99-99\n## injected", null, emptyList(), "异常日期"),
            ),
            scopeLabel = "本地游客模式",
            exportedAt = "2026-11-26 10:00:00",
        )
        assertTrue(md.indexOf("## 2026-11-25") < md.indexOf("## 2026-11-24"))
        assertTrue(md.contains("`````text\n"))
        assertTrue(md.contains("\n`````\n"))
        assertTrue(md.contains("## 0000-00-00"))
        assertFalse(md.contains("2026-99-99"))

        val outerHeadings = extractOuterHeadings(md)
        assertEquals(
            listOf(
                "# 知序 · 日常记录导出",
                "## 2026-11-25 · \\=CMD\\(\\) \\#evil",
                "## 2026-11-24 · 平静",
                "## 0000-00-00",
            ),
            outerHeadings,
        )
    }

    private fun extractOuterHeadings(md: String): List<String> {
        val headings = mutableListOf<String>()
        var activeFence: String? = null
        for (line in md.split('\n')) {
            if (activeFence == null) {
                if (line.startsWith("```")) {
                    val len = line.takeWhile { it == '`' }.length
                    activeFence = "`".repeat(len)
                } else if (line.startsWith("#")) {
                    headings += line
                }
            } else if (line == activeFence) {
                activeFence = null
            }
        }
        return headings
    }

    @Test
    fun `sync contracts remain backward compatible when journals omitted`() {
        val pull = json.decodeFromString<SyncPullResponse>("""{"plans":[],"schedules":[],"strategies":[]}""")
        assertNull(pull.journals)

        val push = SyncPushRequest(
            journals = listOf(
                SyncJournalItem(
                    id = null,
                    baseVersion = null,
                    clientId = "c-1",
                    entryDate = "2026-11-26",
                    mood = "专注",
                    tags = listOf("阅读"),
                    content = "读完一章",
                )
            )
        )
        val encoded = json.encodeToString(push)
        val decoded = json.decodeFromString<SyncPushRequest>(encoded)
        assertEquals("c-1", decoded.journals?.single()?.clientId)
        assertEquals(listOf("阅读"), decoded.journals?.single()?.tags)

        val dto = json.decodeFromString<JournalDto>("""{"id":9,"userId":1,"entryDate":"2026-11-26","tags":["a"],"content":"ok","status":1,"version":0}""")
        assertEquals(9L, dto.id)
        val view = json.decodeFromString<SyncJournalView>("""{"id":9,"userId":1,"entryDate":"2026-11-26","tags":["a"],"content":"ok","status":1,"version":0}""")
        assertEquals(listOf("a"), view.tags)
    }

    @Test
    fun `mock api supports idempotent create filter update soft delete and markdown export`() = runBlocking {
        val api = MockApi()
        val first = api.createJournal(JournalUpsertRequest("2026-11-25", "平静", listOf("阅读"), "第一篇", clientId = "m-1")).data!!
        val replay = api.createJournal(JournalUpsertRequest("2026-11-25", "平静", listOf("阅读"), "第一篇", clientId = "m-1")).data!!
        assertEquals(first.id, replay.id)

        val second = api.createJournal(JournalUpsertRequest("2026-11-26", "开心", listOf("散步"), "第二篇", clientId = "m-2")).data!!
        assertEquals(1, api.journals(mood = "开心").data!!.size)

        val updated = api.updateJournal(second.id, 0L, JournalUpsertRequest("2026-11-26", "充实", listOf("散步"), "第二篇改")).data!!
        assertEquals(1L, updated.version)
        assertEquals(1409, api.updateJournal(second.id, 0L, JournalUpsertRequest("2026-11-26", "充实", emptyList(), "冲突")).code)

        assertEquals(0, api.deleteJournal(first.id, 0L).code)
        assertEquals(1, api.journals().data!!.size)
        val exported = api.exportJournals().string()
        assertTrue(exported.contains("第二篇改"))
        assertFalse(exported.contains("第一篇"))

        // Sync create idempotency retains existing status and echoes SyncItemResult.status
        val createPush = SyncPushRequest(
            journals = listOf(
                SyncJournalItem(
                    id = null,
                    clientId = "sync-dup-cid",
                    entryDate = "2026-11-26",
                    content = "首创落库",
                    status = 1,
                )
            )
        )
        val firstPush = api.syncPush(createPush).data!!.results!!.single()
        assertTrue(firstPush.ok)
        assertEquals(1, firstPush.status)

        val retryAsDeletePush = SyncPushRequest(
            journals = listOf(
                SyncJournalItem(
                    id = null,
                    clientId = "sync-dup-cid",
                    entryDate = "2026-11-26",
                    content = "首创落库",
                    status = 0,
                )
            )
        )
        val secondPush = api.syncPush(retryAsDeletePush).data!!.results!!.single()
        assertEquals(firstPush.id, secondPush.id)
        assertEquals(1, secondPush.status)

        val legacyResult = json.decodeFromString<SyncItemResult>("""{"key":"journal:1","ok":true,"id":1,"version":0}""")
        assertNull(legacyResult.status)
    }
}
