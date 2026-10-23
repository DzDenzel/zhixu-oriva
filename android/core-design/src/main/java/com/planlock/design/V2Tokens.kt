package com.planlock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 兼容层：v2 页面仍按 V2Tokens 取值；所有值已改由 UI 设计规范 v3.0「墨序」定义（源头见 [Ink]）。
 * brand 系列 = 墨块，brandBright = 朱，brandContainer = 朱雾。
 */
object V2Tokens {
    val brand = Ink.ink
    val brandDeep = Color(0xFF2A2F2C)
    val brandBright = Ink.vermilion
    val onBrand = Ink.paperRaised
    val brandContainer = Color(0xFFF2DCD1)
    val onBrandContainer = Color(0xFF3D1209)
    val brandGlow = Ink.vermilion.copy(alpha = .16f)

    data class Palette(
        val surface: Color, val surfaceLow: Color, val surfaceContainer: Color,
        val surfaceHigh: Color, val surfaceHighest: Color,
        val text: Color, val muted: Color, val outline: Color, val outlineVariant: Color,
        val executing: Color, val completed: Color, val violation: Color,
        val unlock: Color, val paused: Color, val upcoming: Color,
    )

    val light = Palette(
        Ink.paper, Ink.paperRaised, Color(0xFFEEE8DC),
        Ink.paperSunk, Ink.paperDeep,
        Ink.ink, Ink.inkSoft, Ink.inkFaint, Ink.hairline,
        Color(0xFFF5DFD5), Color(0xFFDCEAE2), Color(0xFFF4D5D3),
        Color(0xFFF1E4C6), Color(0xFFE6E1D7), Color(0xFFDCE4EE),
    )
    val dark = Palette(
        Ink.night, Ink.nightRaised, Color(0xFF1A201C),
        Ink.nightHigh, Ink.nightHighest,
        Ink.moon, Ink.moonSoft, Ink.moonFaint, Ink.nightHairline,
        Color(0xFF3A1F18), Color(0xFF16302A), Color(0xFF3B1A1C),
        Color(0xFF352A14), Color(0xFF24272A), Color(0xFF1A2635),
    )

    val completed = Ink.jade
    val violation = Ink.crimson
    val unlock = Ink.brass
    val paused = Ink.stone
    val upcoming = Ink.indigo

    // 形状收紧：纸片感，不做大圆角气泡
    val shapeXs = 4.dp
    val shapeSm = 8.dp
    val shapeMd = 12.dp
    val shapeLg = 16.dp
    val shapeXl = 20.dp
    val shapeXxl = 28.dp
    val shapePill = 999.dp

    val numeralHero = TextStyle(fontFamily = Ink.display, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val numeralLarge = TextStyle(fontFamily = Ink.display, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val numeralMedium = TextStyle(fontFamily = Ink.display, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val headlineMedium = TextStyle(fontFamily = Ink.display, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val titleLarge = TextStyle(fontFamily = Ink.display, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val titleMedium = TextStyle(fontFamily = Ink.display, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp)
    val labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val labelMicro = TextStyle(fontFamily = Ink.meta, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .6.sp)

    // 纸面不靠投影分层，靠发丝线；投影只留给浮起的墨块与导航条。
    val e1 = Shadow(1.dp, 2.dp, .04f)
    val e2 = Shadow(2.dp, 8.dp, .06f)
    val e3 = Shadow(10.dp, 28.dp, .14f, 2.dp, 6.dp, .06f)
    val brandE3 = Shadow(14.dp, 32.dp, .22f)
    val glow = Shadow(6.dp, 20.dp, .12f)
    data class Shadow(val y: androidx.compose.ui.unit.Dp, val blur: androidx.compose.ui.unit.Dp, val alpha: Float,
                      val secondY: androidx.compose.ui.unit.Dp = 0.dp,
                      val secondBlur: androidx.compose.ui.unit.Dp = 0.dp, val secondAlpha: Float = 0f)

    const val bentoStaggerMs = Ink.revealStepMs
    const val bentoMaxMs = 460
    const val heroMorphMaxMs = 320
    fun motionDuration(reduced: Boolean, durationMs: Int) = if (reduced) 0 else durationMs
}
