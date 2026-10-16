package com.planlock.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.sp

object PlanLockColors {
    val Brand = V2Tokens.brand
    val BrandContainer = V2Tokens.brandContainer
    val Executing = Ink.vermilion
    val Upcoming = V2Tokens.upcoming
    val Paused = V2Tokens.paused
    val Completed = V2Tokens.completed
    val Danger = V2Tokens.violation
    val Warning = V2Tokens.unlock
}

/** 宣纸：墨为主色，朱为强调（secondary），青为第三色。 */
private val LightColors = lightColorScheme(
    primary = Ink.ink,
    onPrimary = Ink.paperRaised,
    primaryContainer = Color(0xFFE4DCCB),
    onPrimaryContainer = Ink.ink,
    inversePrimary = Ink.moon,
    secondary = Ink.vermilion,
    onSecondary = Color(0xFFFFF6EE),
    secondaryContainer = V2Tokens.brandContainer,
    onSecondaryContainer = V2Tokens.onBrandContainer,
    tertiary = Ink.jade,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E8DE),
    onTertiaryContainer = Color(0xFF0B2C21),
    error = Ink.crimson,
    onError = Color.White,
    errorContainer = Color(0xFFF4D5D3),
    onErrorContainer = Color(0xFF410008),
    background = Ink.paper,
    onBackground = Ink.ink,
    surface = Ink.paper,
    onSurface = Ink.ink,
    surfaceVariant = Ink.paperSunk,
    onSurfaceVariant = Ink.inkSoft,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink.ink,
    inverseOnSurface = Ink.paper,
    surfaceBright = Ink.paperRaised,
    surfaceDim = Ink.paperDeep,
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Ink.paperRaised,
    surfaceContainer = V2Tokens.light.surfaceContainer,
    surfaceContainerHigh = Ink.paperSunk,
    surfaceContainerHighest = Ink.paperDeep,
    outline = Ink.inkFaint,
    outlineVariant = Ink.hairline,
    scrim = Color(0xFF0E1210),
)

/** 夜墨：月白为主色，亮朱为强调。 */
private val DarkColors = darkColorScheme(
    primary = Ink.moon,
    onPrimary = Ink.night,
    primaryContainer = Color(0xFF2E3531),
    onPrimaryContainer = Ink.moon,
    inversePrimary = Ink.ink,
    secondary = Ink.vermilionNight,
    onSecondary = Color(0xFF3A0B02),
    secondaryContainer = Color(0xFF4A2117),
    onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Ink.jadeNight,
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF16302A),
    onTertiaryContainer = Color(0xFFBDEBD7),
    error = Ink.crimsonNight,
    onError = Color(0xFF5C0010),
    errorContainer = Color(0xFF3B1A1C),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink.night,
    onBackground = Ink.moon,
    surface = Ink.night,
    onSurface = Ink.moon,
    surfaceVariant = Ink.nightHigh,
    onSurfaceVariant = Ink.moonSoft,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink.moon,
    inverseOnSurface = Ink.night,
    surfaceBright = Ink.nightHighest,
    surfaceDim = Ink.nightSunk,
    surfaceContainerLowest = Ink.nightSunk,
    surfaceContainerLow = Ink.nightRaised,
    surfaceContainer = V2Tokens.dark.surfaceContainer,
    surfaceContainerHigh = Ink.nightHigh,
    surfaceContainerHighest = Ink.nightHighest,
    outline = Ink.moonFaint,
    outlineVariant = Ink.nightHairline,
    scrim = Color.Black,
)

internal fun brandColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/** 非墨序主题：由色板推导 Material 配色，保证系统组件（开关、对话框、输入框）与主题一致。 */
internal fun paletteColorScheme(p: InkPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.text, onPrimary = p.raised, primaryContainer = p.highest, onPrimaryContainer = p.text,
        inversePrimary = p.raised,
        secondary = p.accent, onSecondary = p.onAccent,
        secondaryContainer = p.accent.copy(alpha = if (p.isDark) .28f else .16f).compositeOver(p.raised),
        onSecondaryContainer = p.text,
        tertiary = p.jade, onTertiary = p.onAccent,
        tertiaryContainer = p.jade.copy(alpha = .2f).compositeOver(p.raised), onTertiaryContainer = p.text,
        error = p.crimson, onError = if (p.isDark) p.ground else Color.White,
        errorContainer = p.crimson.copy(alpha = .18f).compositeOver(p.raised), onErrorContainer = p.text,
        background = p.ground, onBackground = p.text,
        surface = p.ground, onSurface = p.text,
        surfaceVariant = p.sunk, onSurfaceVariant = p.soft,
        surfaceTint = Color.Transparent,
        inverseSurface = p.inverse, inverseOnSurface = p.onInverse,
        surfaceBright = p.raised, surfaceDim = p.sunk,
        surfaceContainerLowest = p.raised, surfaceContainerLow = p.raised,
        surfaceContainer = p.raised, surfaceContainerHigh = p.high, surfaceContainerHighest = p.highest,
        outline = p.faint, outlineVariant = p.hairline,
        scrim = Color.Black,
    )
}

/** 按色板换字体：衬线位换成主题标题字体，正文位换成主题正文字体（若有）。 */
private fun themedTypography(p: InkPalette): Typography {
    if (p.display == FontFamily.Serif && p.body == null && p.meta == FontFamily.Monospace) return PlanTypography
    fun TextStyle.swap(): TextStyle = when (fontFamily) {
        FontFamily.Serif -> copy(fontFamily = p.display)
        FontFamily.Monospace -> copy(fontFamily = p.meta)
        null -> if (p.body != null) copy(fontFamily = p.body) else this
        else -> this
    }
    val t = PlanTypography
    return Typography(
        displayLarge = t.displayLarge.swap(), displayMedium = t.displayMedium.swap(), displaySmall = t.displaySmall.swap(),
        headlineLarge = t.headlineLarge.swap(), headlineMedium = t.headlineMedium.swap(), headlineSmall = t.headlineSmall.swap(),
        titleLarge = t.titleLarge.swap(), titleMedium = t.titleMedium.swap(), titleSmall = t.titleSmall.swap(),
        bodyLarge = t.bodyLarge.swap(), bodyMedium = t.bodyMedium.swap(), bodySmall = t.bodySmall.swap(),
        labelLarge = t.labelLarge.swap(), labelMedium = t.labelMedium.swap(), labelSmall = t.labelSmall.swap(),
    )
}

internal data class SecondaryControlColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

internal val LocalSecondaryControlColors = staticCompositionLocalOf {
    SecondaryControlColors(Ink.vermilion, Color.White, V2Tokens.brandContainer, V2Tokens.onBrandContainer)
}

private val serif = Ink.display
private val PlanTypography = Typography(
    displayLarge = TextStyle(fontFamily = serif, fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontFamily = serif, fontSize = 42.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = serif, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = V2Tokens.headlineMedium,
    headlineSmall = TextStyle(fontFamily = serif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = V2Tokens.titleLarge,
    titleMedium = V2Tokens.titleMedium,
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = V2Tokens.bodyLarge,
    bodyMedium = V2Tokens.bodyMedium,
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = V2Tokens.labelLarge,
    labelMedium = V2Tokens.labelMedium,
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp),
)

private val PlanShapes = Shapes(
    extraSmall = RoundedCornerShape(V2Tokens.shapeXs),
    small = RoundedCornerShape(V2Tokens.shapeSm),
    medium = RoundedCornerShape(V2Tokens.shapeMd),
    large = RoundedCornerShape(V2Tokens.shapeLg),
    extraLarge = RoundedCornerShape(V2Tokens.shapeXl),
)

/**
 * dynamicColor 参数保留以兼容调用方；v3.0 起不再采用系统取色——「墨序」的印色是品牌本身，
 * 跟随壁纸会把朱印变成别的颜色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlanLockTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var reducedMotion by remember { mutableStateOf(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val style = ThemeStyleStore.current
    val darkTheme = style.isDark(themeMode, isSystemInDarkTheme())
    val targetPalette = InkPalettes.of(style, darkTheme)
    // 墨序沿用经过验收的配色靶值；其他主题由色板推导
    val colors = if (style == ThemeStyle.INK) brandColorScheme(darkTheme) else paletteColorScheme(targetPalette)
    // 在同一棵 UI 上混合色板（「墨入纸」420ms），避免主题 Crossfade 重建导航或重复发起业务请求。
    val ms = if (reducedMotion) 0 else 420
    @Composable fun blend(target: Color, label: String) = animateColorAsState(target, tween(ms, easing = Ink.Easing), label = label).value
    val blended = colors.copy(
        primary = blend(colors.primary, "primary"),
        onPrimary = blend(colors.onPrimary, "onPrimary"),
        primaryContainer = blend(colors.primaryContainer, "primaryContainer"),
        onPrimaryContainer = blend(colors.onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = blend(colors.inversePrimary, "inversePrimary"),
        secondary = blend(colors.secondary, "secondary"),
        onSecondary = blend(colors.onSecondary, "onSecondary"),
        secondaryContainer = blend(colors.secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = blend(colors.onSecondaryContainer, "onSecondaryContainer"),
        tertiary = blend(colors.tertiary, "tertiary"),
        onTertiary = blend(colors.onTertiary, "onTertiary"),
        tertiaryContainer = blend(colors.tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = blend(colors.onTertiaryContainer, "onTertiaryContainer"),
        background = blend(colors.background, "background"),
        onBackground = blend(colors.onBackground, "onBackground"),
        surface = blend(colors.surface, "surface"),
        onSurface = blend(colors.onSurface, "onSurface"),
        surfaceVariant = blend(colors.surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = blend(colors.onSurfaceVariant, "onSurfaceVariant"),
        inverseSurface = blend(colors.inverseSurface, "inverseSurface"),
        inverseOnSurface = blend(colors.inverseOnSurface, "inverseOnSurface"),
        error = blend(colors.error, "error"),
        onError = blend(colors.onError, "onError"),
        errorContainer = blend(colors.errorContainer, "errorContainer"),
        onErrorContainer = blend(colors.onErrorContainer, "onErrorContainer"),
        outline = blend(colors.outline, "outline"),
        outlineVariant = blend(colors.outlineVariant, "outlineVariant"),
        surfaceBright = blend(colors.surfaceBright, "surfaceBright"),
        surfaceDim = blend(colors.surfaceDim, "surfaceDim"),
        surfaceContainer = blend(colors.surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = blend(colors.surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = blend(colors.surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = blend(colors.surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = blend(colors.surfaceContainerLowest, "surfaceContainerLowest"),
    )
    val palette = animatePalette(targetPalette, ms)
    val typography = remember(targetPalette.display, targetPalette.body, targetPalette.meta) { themedTypography(targetPalette) }
    CompositionLocalProvider(
        LocalInkPalette provides palette,
        LocalDarkTheme provides darkTheme,
        LocalReducedMotion provides reducedMotion,
        LocalSecondaryControlColors provides SecondaryControlColors(
            palette.accent,
            if (style == ThemeStyle.INK) (if (darkTheme) Color(0xFF3A0B02) else Color.White) else palette.onAccent,
            colors.secondaryContainer,
            colors.onSecondaryContainer,
        ),
    ) {
        MaterialExpressiveTheme(colorScheme = blended, typography = typography, shapes = PlanShapes, content = content)
    }
}
