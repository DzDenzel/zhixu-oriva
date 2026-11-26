package com.planlock.app.record

import com.planlock.app.api.dto.SyncJournalItem
import com.planlock.app.record.entity.PendingRecordEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalMigrationAndProtectionTest {

    @Test
    fun `journal migration targets version 11 to 12`() {
        assertEquals(11, JournalMigration.startVersion)
        assertEquals(12, JournalMigration.endVersion)
    }

    @Test
    fun `snapshot protection and sync entity key cover journal upsert`() {
        val queued = JournalPendingUpsert(
            localId = -101L,
            baseVersion = null,
            item = SyncJournalItem(
                id = 55L,
                baseVersion = 1L,
                clientId = "j-cid",
                entryDate = "2026-11-26",
                mood = "平静",
                tags = listOf("阅读"),
                content = "测试保护",
                status = 1,
            ),
        )
        val pending = PendingRecordEntity(
            type = "journal_upsert",
            planId = null,
            clientId = "j-cid",
            payload = Json.encodeToString(queued),
        )
        assertEquals("journal:-101", pending.syncEntityKey())
        val protection = SnapshotProtection.from(listOf(pending))
        assertTrue(-101L in protection.journals)
        assertTrue(55L in protection.journals)
    }
}
