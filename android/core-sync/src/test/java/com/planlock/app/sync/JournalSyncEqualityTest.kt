package com.planlock.app.sync

import com.planlock.app.api.dto.SyncJournalItem
import com.planlock.app.api.dto.SyncJournalView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSyncEqualityTest {

    private fun item(status: Int = 1, mood: String? = "平静") = SyncJournalItem(
        id = 12L,
        baseVersion = 0L,
        clientId = "cid-12",
        entryDate = "2026-11-26",
        mood = mood,
        tags = listOf("阅读", "复盘"),
        content = "晚间复盘",
        status = status,
    )

    private fun view(status: Int = 1, mood: String? = "平静") = SyncJournalView(
        id = 12L,
        userId = 1L,
        clientId = "cid-12",
        entryDate = "2026-11-26",
        mood = mood,
        tags = listOf("阅读", "复盘"),
        content = "晚间复盘",
        status = status,
        version = 1L,
    )

    @Test
    fun `sameJournal confirms lost-response retry and detects real differences`() {
        assertTrue(sameJournal(item(), view()))
        assertTrue(sameJournal(item(status = 0), view(status = 0)))
        assertFalse(sameJournal(item(mood = "平静"), view(mood = "开心")))
        assertFalse(sameJournal(item(status = 0), view(status = 1)))
    }
}
