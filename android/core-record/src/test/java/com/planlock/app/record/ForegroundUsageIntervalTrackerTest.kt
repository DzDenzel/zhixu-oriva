package com.planlock.app.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundUsageIntervalTrackerTest {
    @Test
    fun repeatedEventsForSamePackageStayInOneInterval() {
        val tracker = ForegroundUsageIntervalTracker()

        assertNull(tracker.observe(tracked(7, "video", "Video"), 1_000))
        assertNull(tracker.observe(tracked(7, "video", "Video detail"), 2_000))
        val closed = tracker.observe(untracked(7, "launcher"), 6_000)

        assertEquals(
            ForegroundUsageInterval(
                planId = 7,
                packageName = "video",
                appName = "Video detail",
                startedAtMillis = 1_000,
                endedAtMillis = 6_000,
                durationMillis = 5_000,
                source = ObservationSource.ACCESSIBILITY,
            ),
            closed,
        )
    }

    @Test
    fun switchingAwayAndBackCreatesTwoCompleteIntervals() {
        val tracker = ForegroundUsageIntervalTracker()

        tracker.observe(tracked(7, "video"), 1_000)
        val first = tracker.observe(tracked(7, "chat"), 4_000)
        val second = tracker.observe(tracked(7, "video"), 9_000)
        val third = tracker.close(11_000)

        assertEquals("video", first?.packageName)
        assertEquals(3_000L, first?.durationMillis)
        assertEquals("chat", second?.packageName)
        assertEquals(5_000L, second?.durationMillis)
        assertEquals("video", third?.packageName)
        assertEquals(2_000L, third?.durationMillis)
    }

    @Test
    fun sourceOrPlanChangeClosesThePreviousInterval() {
        val tracker = ForegroundUsageIntervalTracker()
        tracker.observe(tracked(7, "video"), 1_000)

        val first = tracker.observe(
            tracked(8, "video", source = ObservationSource.USAGE_STATS_APPROXIMATE),
            3_000,
        )

        assertEquals(7L, first?.planId)
        assertEquals(ObservationSource.ACCESSIBILITY, first?.source)
        assertEquals(8L, tracker.active()?.planId)
        assertEquals(ObservationSource.USAGE_STATS_APPROXIMATE, tracker.active()?.source)
    }

    private fun tracked(
        planId: Long,
        packageName: String,
        appName: String = packageName,
        source: ObservationSource = ObservationSource.ACCESSIBILITY,
    ) = ForegroundObservation(planId, packageName, appName, shouldTrack = true, source)

    private fun untracked(planId: Long, packageName: String) =
        ForegroundObservation(planId, packageName, packageName, shouldTrack = false, ObservationSource.ACCESSIBILITY)
}
