package com.planlock.feature.plan

import com.planlock.app.api.WeatherLocation
import com.planlock.app.api.WeatherSnapshot
import com.planlock.app.api.dto.StatsResponse
import com.planlock.app.api.dto.TodaySummary
import com.planlock.app.record.entity.PlanSnapshotEntity
import com.planlock.app.record.entity.ScheduleSnapshotEntity
import com.planlock.app.sync.SyncOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodayLocalReaderTest {
    private val plan = PlanSnapshotEntity(7, 42, "计划", "学习", "21:00", "23:00", null, null, null, 0, null, 1, 0)
    private val schedule = ScheduleSnapshotEntity(
        8, 42, "日程", "2026-10-04", null, "2026-10-04", null,
        true, null, null, null, null, null, false, "GMT", 1, 0,
    )
    private val weather = WeatherSnapshot(WeatherLocation("本地天气", 1.0, 2.0, "GMT"), 26.0, 3, "10:00", 123)

    @Test
    fun `preload delivers plans schedules and weather without waiting for page refresh`() = runBlocking {
        val reader = TodayLocalReader(
            { listOf(plan) },
            { listOf(schedule) },
            { owner ->
                assertEquals(42L, owner)
                SyncOverview(TodaySummary("2026-10-04", null, 3, 2), StatsResponse(continuousDays = 5))
            },
            { weather },
        )
        val result = reader.read(42, "2026-10-04")
        assertNull(result.error)
        val data = requireNotNull(result.data)
        assertEquals(listOf("计划"), data.plans.map { it.name })
        assertEquals(listOf("日程"), data.schedules.map { it.title })
        assertEquals(26.0, requireNotNull(data.weather).temperatureC, .001)
        assertEquals(3, data.violations)
        assertEquals(2, data.unlocks)
        assertEquals(5, data.streak)
    }

    @Test
    fun `previous day counts do not leak into today's first frame`() = runBlocking {
        val reader = TodayLocalReader(
            { emptyList() },
            { emptyList() },
            { SyncOverview(TodaySummary("2026-10-03", null, 9, 8), StatsResponse(continuousDays = 4)) },
            { null },
        )
        val data = requireNotNull(reader.read(null, "2026-10-04").data)
        assertEquals(0, data.violations)
        assertEquals(0, data.unlocks)
        assertEquals(4, data.streak)
    }

    @Test
    fun `missing local data is successful empty content not a read failure`() = runBlocking {
        val result = TodayLocalReader({ emptyList() }, { emptyList() }, { SyncOverview() }, { null })
            .read(null, "2026-10-04")
        assertNull(result.error)
        assertTrue(requireNotNull(result.data).plans.isEmpty())
        assertTrue(result.data!!.schedules.isEmpty())
    }

    @Test
    fun `disk failure is visible rather than disguised as empty content`() = runBlocking {
        val result = TodayLocalReader(
            { throw IllegalStateException("disk") },
            { emptyList() },
            { SyncOverview() },
            { null },
        ).read(null, "2026-10-04")
        assertNull(result.data)
        assertNotNull(result.error)
    }

    @Test
    fun `cancelled activity read cannot become a completed failure seed`() = runBlocking {
        try {
            TodayLocalReader(
                { throw CancellationException("destroyed") },
                { emptyList() },
                { SyncOverview() },
                { null },
            ).read(null, "2026-10-04")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
