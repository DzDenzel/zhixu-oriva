package com.planlock.app.record

import com.planlock.app.record.entity.PlanSnapshotEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EndedOccurrenceTest {
    private fun plan(start: String = "09:00", end: String = "10:00") = Json { ignoreUnknownKeys = true }.decodeFromString<PlanSnapshotEntity>(
        """{"id":7,"userId":1,"name":"专注","type":"study","startTime":"$start","endTime":"$end","repeatRule":null,"color":null,"icon":null,"remindAdvanceMin":15,"remindMethods":null,"status":1,"version":0,"endedOccurrenceDate":"2026-10-16"}"""
    )
    @Test fun persistedEndedOccurrenceSkipsTodayAndAllowsTomorrow() {
        assertFalse(PlanOccurrence.isActive(plan(), 570, OccurrenceDate(2026,10,16,5)))
        assertTrue(PlanOccurrence.isActive(plan(), 570, OccurrenceDate(2026,10,17,6)))
    }
    @Test fun overnightTailSkipsStartDateRatherThanClickDate() {
        assertFalse(PlanOccurrence.isActive(plan("23:00", "06:00"), 60, OccurrenceDate(2026,10,17,6)))
        assertTrue(PlanOccurrence.isActive(plan("23:00", "06:00"), 23*60, OccurrenceDate(2026,10,17,6)))
    }
    @Test fun todayTimelineAndBriefCannotStillClaimEndedOccurrenceIsRunning() {
        val entry=DayTimeline.build("2026-10-16",listOf(plan()),emptyList(),570).single()
        assertEquals(TimelineState.ENDED,entry.state)
        val tail=DayTimeline.build("2026-10-17",listOf(plan("23:00","06:00")),emptyList(),60).single { it.clippedStart }
        assertEquals(TimelineState.ENDED,tail.state)
    }
}
