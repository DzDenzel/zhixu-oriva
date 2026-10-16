package com.planlock.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandColorSchemeTest {
    @Test
    fun lightPageSkeletonIsInkOnPaper() {
        val colors = brandColorScheme(dark = false)

        assertEquals(Color(0xFF1A1D1B), colors.primary)
        assertEquals(Color(0xFFC8412B), colors.secondary)
        assertEquals(Color(0xFFF2EDE3), colors.surface)
        assertEquals(Color(0xFFFAF7F1), colors.surfaceContainerLow)
        assertEquals(Color(0xFFEEE8DC), colors.surfaceContainer)
        assertEquals(Color(0xFFEAE3D6), colors.surfaceContainerHigh)
    }

    @Test
    fun darkPageSkeletonIsMoonOnNightInk() {
        val colors = brandColorScheme(dark = true)

        assertEquals(Color(0xFFECE5D8), colors.primary)
        assertEquals(Color(0xFFFF7D5E), colors.secondary)
        assertEquals(Color(0xFF0E1210), colors.surface)
        assertEquals(Color(0xFF161B18), colors.surfaceContainerLow)
        assertEquals(Color(0xFF1A201C), colors.surfaceContainer)
        assertEquals(Color(0xFF1E2420), colors.surfaceContainerHigh)
    }
}
