package com.planlock.feature.plan

import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherSnapshot
import com.planlock.app.api.dto.SyncJournalView
import com.planlock.app.record.SyncConfirmation
import com.planlock.app.record.entity.JournalSnapshotEntity
import com.planlock.app.sync.SyncOverview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalPresentationTest {

    private fun entry(
        id: Long,
        date: String,
        mood: String?,
        tagsJson: String?,
        content: String,
        status: Int = 1,
        scope: String = "instance\n1",
    ) = JournalSnapshotEntity(
        id = id,
        userId = 1L,
        clientId = "cid-$id",
        entryDate = date,
        mood = mood,
        tagsJson = tagsJson,
        content = content,
        status = status,
        version = 0L,
        scope = scope,
    )

    @Test
    fun `filters by mood tag and keyword in descending date and id order`() {
        val entries = listOf(
            entry(1, "2026-11-24", "平静", """["散步","夜晚"]""", "晚风很舒服"),
            entry(2, "2026-11-26", "专注", """["编码"]""", "完成日常记录模块"),
            entry(3, "2026-11-26", "开心", """["复盘","编码"]""", "通过所有测试"),
            entry(4, "2026-11-26", "平静", """["散步"]""", "已删除记录", status = 0),
        )
        assertEquals(listOf(3L, 2L, 1L), filterJournalEntries(entries).map { it.id })
        assertEquals(listOf(1L), filterJournalEntries(entries, mood = "平静").map { it.id })
        assertEquals(listOf(3L, 2L), filterJournalEntries(entries, tag = "编码").map { it.id })
        assertEquals(listOf(3L), filterJournalEntries(entries, tag = "编码", query = "测试").map { it.id })
        assertEquals(listOf("散步", "夜晚", "编码", "复盘"), distinctJournalTags(entries))
    }

    @Test
    fun `scope guards invalidate stale state reject cross-instance commits and block stale export`() {
        val scopeA = "instanceA\n1"
        val scopeB = "instanceB\n1"
        val itemA = entry(42L, "2026-11-26", "平静", null, "A空间私密日记", scope = scopeA)
        val stateA = ScopedJournalsState(scope = scopeA, entries = listOf(itemA), loading = false)

        // Switching to another instance with identical remote ID 42 must expose zero entries.
        assertEquals(listOf(42L), visibleJournalsForScope(stateA, scopeA).map { it.id })
        assertTrue(visibleJournalsForScope(stateA, scopeB).isEmpty())

        // Cross-scope save/delete/export must be rejected even if numeric ID matches.
        assertTrue(canCommitJournalInScope(scopeA, scopeA, itemA))
        assertFalse(canCommitJournalInScope(scopeA, scopeB, itemA))
        assertFalse(canCommitJournalInScope(scopeB, scopeB, itemA))

        assertNotNull(prepareScopedJournalExport(listOf(itemA), scopeA, scopeA))
        assertNull(prepareScopedJournalExport(listOf(itemA), scopeA, scopeB))
        assertNull(prepareScopedJournalExport(listOf(itemA), scopeB, scopeB))
    }

    @Test
    fun `journal conflict presentation summarizes both versions clearly`() {
        val local = entry(7L, "2026-11-26", "专注", """["编码"]""", "本机修改正文")
        val remote = SyncJournalView(
            id = 7L,
            userId = 1L,
            entryDate = "2026-11-26",
            mood = "平静",
            tags = listOf("阅读"),
            content = "云端修改正文",
            status = 1,
            version = 2L,
        )
        val presentation = SyncConfirmation(
            kind = "journal",
            id = 7L,
            localJournal = local,
            remoteJournal = remote,
        ).presentation()
        assertTrue(presentation.title.contains("2026-11-26"))
        assertEquals(4, presentation.fields.size)
        assertEquals("保留对方版本", presentation.keepRemoteLabel)
        assertEquals("保留我的版本", presentation.keepMineLabel)
    }

    @Test
    fun `validates journal form boundaries`() {
        assertTrue(journalFormIssues("2026-11-26", "平静", listOf("日常"), "今天很安静").isEmpty())
        assertEquals(listOf("content"), journalFormIssues("2026-11-26", null, emptyList(), "   ").map { it.field })
        assertEquals(listOf("date", "content"), journalFormIssues("2026-02-31", null, emptyList(), "").map { it.field })
        assertEquals(listOf("tags"), journalFormIssues("2026-11-26", null, (1..9).map { "t$it" }, "ok").map { it.field })
    }

    @Test
    fun `today local reader loads scoped journals`() = runBlocking {
        val row = entry(10L, "2026-11-26", "平静", null, "早起读书")
        val reader = TodayLocalReader(
            plans = { emptyList() },
            schedules = { emptyList() },
            overview = { SyncOverview() },
            weather = { WeatherSnapshot(WeatherLocation("杭州", 30.0, 120.0, "Asia/Shanghai"), 18.0, 0, "08:00", 1L) },
            scopedPlans = { emptyList() },
            scopedSchedules = { emptyList() },
            scopedOverview = { SyncOverview() },
            journals = { listOf(row) },
            scopedJournals = { scope -> if (scope == "instance\n1") listOf(row) else emptyList() },
        )
        val ok = reader.read(1L, "2026-11-26", "instance\n1")
        assertEquals(listOf(10L), ok.data?.journals?.map { it.id })
    }
}
