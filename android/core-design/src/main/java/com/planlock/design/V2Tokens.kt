package com.planlock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Values approved in UI 设计规范 v2.0. Keep this the single source for Compose surfaces. */
object V2Tokens {
    val brand = Color(0xFF182F20)
    val brandDeep = Color(0xFF173F35)
    val brandBright = Color(0xFF2E7A66)
    val onBrand = Color.White
    val brandContainer = Color(0xFFA8F2D8)
    val onBrandContainer = Color(0xFF002019)
    val brandGlow = brand.copy(alpha = .14f)

    data class Palette(
        val surface: Color, val surfaceLow: Color, val surfaceContainer: Color,
        val surfaceHigh: Color, val surfaceHighest: Color,
        val text: Color, val muted: Color, val outline: Color, val outlineVariant: Color,
        val executing: Color, val completed: Color, val violation: Color,
        val unlock: Color, val paused: Color, val upcoming: Color,
    )

    val light = Palette(
        Color(0xFFFBFDF9), Color(0xFFF3F5F1), Color(0xFFEDF0EB),
        Color(0xFFE4E8E2), Color(0xFFDCE1DA),
        Color(0xFF191C1A), Color(0xFF414946), Color(0xFF717A76), Color(0xFFC0C9C4),
        Color(0xFFDCF2E8), Color(0xFFD7F0E2), Color(0xFFFBD9D9),
        Color(0xFFF7E3C0), Color(0xFFE4E7EA), Color(0xFFD6E3F0),
    )
    val dark = Palette(
        Color(0xFF181F1A), Color(0xFF1E2621), Color(0xFF242D27),
        Color(0xFF2B352E), Color(0xFF333E36),
        Color(0xFFE6EAE4), Color(0xFFA8B2AB), Color(0xFF889D8D), Color(0xFF3A443E),
        Color(0xFF1F3A31), Color(0xFF1E3527), Color(0xFF3B1F1F),
        Color(0xFF3A2C14), Color(0xFF2A2E31), Color(0xFF1D2A35),
    )

    val completed = Color(0xFF2F6B4F)
    val violation = Color(0xFFBA1A1A)
    val unlock = Color(0xFF8A5700)
    val paused = Color(0xFF6B7280)
    val upcoming = Color(0xFF45617B)

    val shapeXs = 8.dp
    val shapeSm = 12.dp
    val shapeMd = 16.dp
    val shapeLg = 24.dp
    val shapeXl = 28.dp
    val shapeXxl = 36.dp
    val shapePill = 999.dp

    val numeralHero = TextStyle(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val numeralLarge = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val numeralMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val labelMicro = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

    // CSS shadow targets: e1 1/3 .06 + 1/2 .04; e2 4/12 .08 + 1/3 .05;
    // e3 12/32 .12 + 2/6 .06; brandE3 16/36 .18; glow 8/24 .10.
    val e1 = Shadow(1.dp, 3.dp, .06f, 1.dp, 2.dp, .04f)
    val e2 = Shadow(4.dp, 12.dp, .08f, 1.dp, 3.dp, .05f)
    val e3 = Shadow(12.dp, 32.dp, .12f, 2.dp, 6.dp, .06f)
    val brandE3 = Shadow(16.dp, 36.dp, .18f)
    val glow = Shadow(8.dp, 24.dp, .10f)
    data class Shadow(val y: androidx.compose.ui.unit.Dp, val blur: androidx.compose.ui.unit.Dp, val alpha: Float,
                      val secondY: androidx.compose.ui.unit.Dp = 0.dp,
                      val secondBlur: androidx.compose.ui.unit.Dp = 0.dp, val secondAlpha: Float = 0f)

    const val bentoStaggerMs = 40
    const val bentoMaxMs = 320
    const val heroMorphMaxMs = 300
    fun motionDuration(reduced: Boolean, durationMs: Int) = if (reduced) 0 else durationMs
}
