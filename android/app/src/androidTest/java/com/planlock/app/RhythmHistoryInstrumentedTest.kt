package com.planlock.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.planlock.app.api.dto.*
import com.planlock.app.record.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Owns only an in-memory DB: never clears the installed application's account or QA records. */
class RhythmHistoryInstrumentedTest {
    @Test fun acknowledgedBehaviorSurvivesAndRemoteReplayDoesNotDuplicateOrCrossScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlanLockDatabase::class.java).build()
        try {
            val store = RecordStore(db, historyScope = { "rhythm-test\n1" })
            val request = ViolationRecordRequest(planId = 7, recordAt = "2026-10-04T09:00:00",
                appName = "例子", packageName = "example.app", startedAt = "2026-10-04T09:00:00",
                endedAt = "2026-10-04T09:02:00", durationMs = 120_000, clientId = UUID.randomUUID().toString())
            store.enqueueViolation(request)
            val before = store.behaviorHistory()
            assertEquals(1, before.size)
            store.removePending(store.pending().map { it.id })
            assertEquals(before, store.behaviorHistory())
            val remote = before.single().copy(id = 99)
            store.mergeHistory(RecordHistoryPage(behaviors = listOf(remote), behaviorCursor = 99))
            assertEquals(1, store.behaviorHistory().size)
            assertEquals(99L, store.historyCursor().behaviorId)
            assertTrue(store.forScope("rhythm-test\n2").behaviorHistory().isEmpty())
        } finally { db.close() }
    }

    @Test fun oneYearProjectionCanBeTimedWithoutChangingAnyPersistentData() {
        val today = requireNotNull(OccurrenceDate.parseIso("2026-10-04"))
        val rows = (0..364).flatMap { day -> (1..12).map { id ->
            val date = today.plusDays(-day).let { "%04d-%02d-%02d".format(it.year,it.month,it.day) }
            ExecutionRecordDto(id = day * 12L + id, planId = id.toLong(), planDate = date, status = 1,
                startedAt = "${date}T09:00:00", endedAt = "${date}T10:00:00")
        } }
        val plans = (1..12).map { id -> com.planlock.app.record.entity.PlanSnapshotEntity(id.toLong(), 1,
            "计划$id", "学习", "09:00", "10:00", null, null, null, 15, null, 1, 1) }
        repeat(3) { RhythmStats.aggregate(plans, rows, emptyList(), today) }
        val start = System.nanoTime()
        val summary = RhythmStats.aggregate(plans, rows, emptyList(), today)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        android.util.Log.i("RhythmPerformance", "rows=${rows.size}, elapsedMs=$elapsedMs, model=${android.os.Build.MODEL}")
        assertEquals(365, summary.streakDays)
        assertEquals(21_600, summary.focusMinutes30d)
        assertTrue("REQ-042 D4: elapsedMs=$elapsedMs", elapsedMs <= 300)
    }
}
