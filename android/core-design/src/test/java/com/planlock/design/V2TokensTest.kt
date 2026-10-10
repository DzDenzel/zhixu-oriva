package com.planlock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class V2TokensTest {
    @Test fun approvedPaletteAndMetricsAreExact() {
        assertEquals(Color(0xFF182F20), V2Tokens.brand)
        assertEquals(Color(0xFF173F35), V2Tokens.brandDeep)
        assertEquals(Color(0xFF2E7A66), V2Tokens.brandBright)
        assertEquals(Color(0xFFFBFDF9), V2Tokens.light.surface)
        assertEquals(Color(0xFFF3F5F1), V2Tokens.light.surfaceLow)
        assertEquals(Color(0xFFEDF0EB), V2Tokens.light.surfaceContainer)
        assertEquals(Color(0xFFE4E8E2), V2Tokens.light.surfaceHigh)
        assertEquals(Color(0xFFDCE1DA), V2Tokens.light.surfaceHighest)
        assertEquals(Color(0xFF181F1A), V2Tokens.dark.surface)
        assertEquals(Color(0xFF1E2621), V2Tokens.dark.surfaceLow)
        assertEquals(Color(0xFF242D27), V2Tokens.dark.surfaceContainer)
        assertEquals(Color(0xFF2B352E), V2Tokens.dark.surfaceHigh)
        assertEquals(Color(0xFF333E36), V2Tokens.dark.surfaceHighest)
        assertEquals(8.dp, V2Tokens.shapeXs)
        assertEquals(12.dp, V2Tokens.shapeSm)
        assertEquals(16.dp, V2Tokens.shapeMd)
        assertEquals(24.dp, V2Tokens.shapeLg)
        assertEquals(28.dp, V2Tokens.shapeXl)
        assertEquals(36.dp, V2Tokens.shapeXxl)
        assertEquals(56.sp, V2Tokens.numeralHero.fontSize)
        assertEquals(60.sp, V2Tokens.numeralHero.lineHeight)
        assertEquals(40, V2Tokens.bentoStaggerMs)
        assertEquals(0, V2Tokens.motionDuration(true, 240))
        assertEquals(240, V2Tokens.motionDuration(false, 240))
    }
}
