package com.planlock.feature.plan

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactTimelineSpacingTest {
    @Test
    fun `adjacent compact cards are separated without adding space after the last card`() {
        assertEquals(8, compactTimelineBottomSpacing(index = 0, itemCount = 3))
        assertEquals(8, compactTimelineBottomSpacing(index = 1, itemCount = 3))
        assertEquals(0, compactTimelineBottomSpacing(index = 2, itemCount = 3))
        assertEquals(0, compactTimelineBottomSpacing(index = 0, itemCount = 1))
    }
}
