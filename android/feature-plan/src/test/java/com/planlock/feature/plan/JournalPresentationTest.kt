package com.planlock.feature.plan

import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherSnapshot
import com.planlock.app.record.entity.JournalSnapshotEntity
import com.planlock.app.sync.SyncOverview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        scope = "instance\n1",
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
