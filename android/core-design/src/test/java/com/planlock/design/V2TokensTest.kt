package com.planlock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI 设计规范 v3.0「墨序」靶值：V2Tokens 作为兼容层，取值全部来自 [Ink]。 */
class V2TokensTest {
    @Test fun approvedPaletteAndMetricsAreExact() {
        assertEquals(Color(0xFF1A1D1B), V2Tokens.brand)
        assertEquals(Color(0xFF2A2F2C), V2Tokens.brandDeep)
        assertEquals(Color(0xFFC8412B), V2Tokens.brandBright)
        assertEquals(Color(0xFFF2EDE3), V2Tokens.light.surface)
        assertEquals(Color(0xFFFAF7F1), V2Tokens.light.surfaceLow)
        assertEquals(Color(0xFFEEE8DC), V2Tokens.light.surfaceContainer)
        assertEquals(Color(0xFFEAE3D6), V2Tokens.light.surfaceHigh)
        assertEquals(Color(0xFFDDD4C3), V2Tokens.light.surfaceHighest)
        assertEquals(Color(0xFF0E1210), V2Tokens.dark.surface)
        assertEquals(Color(0xFF161B18), V2Tokens.dark.surfaceLow)
        assertEquals(Color(0xFF1A201C), V2Tokens.dark.surfaceContainer)
        assertEquals(Color(0xFF1E2420), V2Tokens.dark.surfaceHigh)
        assertEquals(Color(0xFF272E2A), V2Tokens.dark.surfaceHighest)
        assertEquals(4.dp, V2Tokens.shapeXs)
        assertEquals(8.dp, V2Tokens.shapeSm)
        assertEquals(12.dp, V2Tokens.shapeMd)
        assertEquals(16.dp, V2Tokens.shapeLg)
        assertEquals(20.dp, V2Tokens.shapeXl)
        assertEquals(28.dp, V2Tokens.shapeXxl)
        assertEquals(56.sp, V2Tokens.numeralHero.fontSize)
        assertEquals(60.sp, V2Tokens.numeralHero.lineHeight)
        assertEquals(45, V2Tokens.bentoStaggerMs)
        assertEquals(0, V2Tokens.motionDuration(true, 240))
        assertEquals(240, V2Tokens.motionDuration(false, 240))
    }

    @Test fun sealColorsKeepTheirMeaningAcrossThemes() {
        assertEquals(Color(0xFFC8412B), Ink.vermilion)
        assertEquals(Color(0xFFFF7D5E), Ink.vermilionNight)
        assertEquals(Color(0xFF2E6B56), Ink.jade)
        assertEquals(Color(0xFF9C6F22), Ink.brass)
        assertEquals(Color(0xFF3D5878), Ink.indigo)
        assertEquals(Ink.vermilion, PlanLockColors.Executing)
    }
}
