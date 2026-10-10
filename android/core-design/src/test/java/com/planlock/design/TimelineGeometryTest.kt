package com.planlock.design

import org.junit.Assert.*
import org.junit.Test

class TimelineGeometryTest {
    @Test fun coordinatesAndMinimumHeight() {
        val g = TimelineGeometry.block(480, 1440, 540, 550, 264f, 0, 1)
        assertEquals(56f, g.top, .001f)
        assertEquals(36f, g.height, .001f)
    }
    @Test fun columnsFitNarrowAndWideScreens() {
        for (screen in listOf(360f, 480f)) {
            val canvas = screen - 48f
            val available = canvas - 52f - 12f
            val first = TimelineGeometry.block(0, 1440, 0, 60, available, 0, 2)
            val last = TimelineGeometry.block(0, 1440, 0, 60, available, 1, 2)
            assertEquals((available - 6f) / 2f, first.width, .001f)
            assertEquals(first.width + 6f, last.left, .001f)
            assertEquals(available, last.left + last.width, .001f)
        }
    }
    @Test fun clippedCoordinatesStayInsideWindow() {
        val g = TimelineGeometry.block(0, 480, -60, 60, 248f, 0, 1)
        assertEquals(0f, g.top, .001f)
        assertEquals(56f, g.height, .001f)
    }
}
