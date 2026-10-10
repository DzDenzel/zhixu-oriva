package com.planlock.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandColorSchemeTest {
    @Test
    fun lightPageSkeletonUsesApprovedBrandAndSurfaceTokens() {
        val colors = brandColorScheme(dark = false)

        assertEquals(Color(0xFF182F20), colors.primary)
        assertEquals(Color(0xFFFBFDF9), colors.surface)
        assertEquals(Color(0xFFF3F5F1), colors.surfaceContainerLow)
        assertEquals(Color(0xFFEDF0EB), colors.surfaceContainer)
        assertEquals(Color(0xFFE4E8E2), colors.surfaceContainerHigh)
    }

    @Test
    fun darkPageSkeletonUsesApprovedBrandAndSurfaceTokens() {
        val colors = brandColorScheme(dark = true)

        assertEquals(Color(0xFF8DD7BD), colors.primary)
        assertEquals(Color(0xFF181F1A), colors.surface)
        assertEquals(Color(0xFF1E2621), colors.surfaceContainerLow)
        assertEquals(Color(0xFF242D27), colors.surfaceContainer)
        assertEquals(Color(0xFF2B352E), colors.surfaceContainerHigh)
    }
}
