package com.planlock.design

import android.os.Build
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object PlanLockColors {
    val Brand = V2Tokens.brand
    val BrandContainer = V2Tokens.brandContainer
    val Executing = V2Tokens.brand
    val Upcoming = V2Tokens.upcoming
    val Paused = V2Tokens.paused
    val Completed = V2Tokens.completed
    val Danger = V2Tokens.violation
    val Warning = V2Tokens.unlock
}

private val LightColors = lightColorScheme(
    primary = PlanLockColors.Brand,
    onPrimary = V2Tokens.onBrand,
    primaryContainer = PlanLockColors.BrandContainer,
    onPrimaryContainer = V2Tokens.onBrandContainer,
    secondary = Color(0xFF4C635B),
    tertiary = Color(0xFF3F6374),
    error = PlanLockColors.Danger,
    background = V2Tokens.light.surface,
    onBackground = V2Tokens.light.text,
    surface = V2Tokens.light.surface,
    onSurface = V2Tokens.light.text,
    surfaceVariant = V2Tokens.light.surfaceContainer,
    onSurfaceVariant = V2Tokens.light.muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = V2Tokens.light.surfaceLow,
    surfaceContainer = V2Tokens.light.surfaceContainer,
    surfaceContainerHigh = V2Tokens.light.surfaceHigh,
    surfaceContainerHighest = V2Tokens.light.surfaceHighest,
    outline = V2Tokens.light.outline,
    outlineVariant = V2Tokens.light.outlineVariant,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DD7BD),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF07513F),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFFB3CCC1),
    tertiary = Color(0xFFA7CDDF),
    error = Color(0xFFFFB4AB),
    background = V2Tokens.dark.surface,
    onBackground = V2Tokens.dark.text,
    surface = V2Tokens.dark.surface,
    onSurface = V2Tokens.dark.text,
    surfaceVariant = V2Tokens.dark.surfaceContainer,
    onSurfaceVariant = V2Tokens.dark.muted,
    surfaceContainerLowest = Color(0xFF101612),
    surfaceContainerLow = V2Tokens.dark.surfaceLow,
    surfaceContainer = V2Tokens.dark.surfaceContainer,
    surfaceContainerHigh = V2Tokens.dark.surfaceHigh,
    surfaceContainerHighest = V2Tokens.dark.surfaceHighest,
    outline = V2Tokens.dark.outline,
    outlineVariant = V2Tokens.dark.outlineVariant,
)

internal fun brandColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

internal data class SecondaryControlColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

internal val LocalSecondaryControlColors = staticCompositionLocalOf {
    SecondaryControlColors(
        PlanLockColors.Brand,
        Color.White,
        PlanLockColors.BrandContainer,
        Color(0xFF002019),
    )
}

private val PlanTypography = Typography(
    headlineMedium = V2Tokens.headlineMedium,
    titleLarge = V2Tokens.titleLarge,
    titleMedium = V2Tokens.titleMedium,
    bodyLarge = V2Tokens.bodyLarge,
    bodyMedium = V2Tokens.bodyMedium,
    labelLarge = V2Tokens.labelLarge,
    labelMedium = V2Tokens.labelMedium,
)

private val PlanShapes = Shapes(
    extraSmall = RoundedCornerShape(V2Tokens.shapeXs),
    small = RoundedCornerShape(V2Tokens.shapeSm),
    medium = RoundedCornerShape(V2Tokens.shapeMd),
    large = RoundedCornerShape(V2Tokens.shapeLg),
    extraLarge = RoundedCornerShape(V2Tokens.shapeXl)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlanLockTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
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
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val colors = brandColorScheme(darkTheme)
    val secondaryColors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> colors
    }
    // 在同一棵 UI 上混合色板，避免主题 Crossfade 重建导航或重复发起业务请求。
    val blended = colors.copy(
        primary = animateColorAsState(colors.primary, tween(if (reducedMotion) 0 else 250), label = "theme-primary").value,
        onPrimary = animateColorAsState(colors.onPrimary, tween(if (reducedMotion) 0 else 250), label = "theme-onPrimary").value,
        primaryContainer = animateColorAsState(colors.primaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-primaryContainer").value,
        onPrimaryContainer = animateColorAsState(colors.onPrimaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-onPrimaryContainer").value,
        inversePrimary = animateColorAsState(colors.inversePrimary, tween(if (reducedMotion) 0 else 250), label = "theme-inversePrimary").value,
        secondary = animateColorAsState(colors.secondary, tween(if (reducedMotion) 0 else 250), label = "theme-secondary").value,
        onSecondary = animateColorAsState(colors.onSecondary, tween(if (reducedMotion) 0 else 250), label = "theme-onSecondary").value,
        secondaryContainer = animateColorAsState(colors.secondaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(colors.onSecondaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-onSecondaryContainer").value,
        tertiary = animateColorAsState(colors.tertiary, tween(if (reducedMotion) 0 else 250), label = "theme-tertiary").value,
        onTertiary = animateColorAsState(colors.onTertiary, tween(if (reducedMotion) 0 else 250), label = "theme-onTertiary").value,
        tertiaryContainer = animateColorAsState(colors.tertiaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(colors.onTertiaryContainer, tween(if (reducedMotion) 0 else 250), label = "theme-onTertiaryContainer").value,
        background = animateColorAsState(colors.background, tween(if (reducedMotion) 0 else 250), label = "theme-background").value,
        onBackground = animateColorAsState(colors.onBackground, tween(if (reducedMotion) 0 else 250), label = "theme-onBackground").value,
        surface = animateColorAsState(colors.surface, tween(if (reducedMotion) 0 else 250), label = "theme-surface").value,
        onSurface = animateColorAsState(colors.onSurface, tween(if (reducedMotion) 0 else 250), label = "theme-onSurface").value,
        surfaceVariant = animateColorAsState(colors.surfaceVariant, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(colors.onSurfaceVariant, tween(if (reducedMotion) 0 else 250), label = "theme-onSurfaceVariant").value,
        surfaceTint = animateColorAsState(colors.surfaceTint, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceTint").value,
        inverseSurface = animateColorAsState(colors.inverseSurface, tween(if (reducedMotion) 0 else 250), label = "theme-inverseSurface").value,
        inverseOnSurface = animateColorAsState(colors.inverseOnSurface, tween(if (reducedMotion) 0 else 250), label = "theme-inverseOnSurface").value,
        error = animateColorAsState(colors.error, tween(if (reducedMotion) 0 else 250), label = "theme-error").value,
        onError = animateColorAsState(colors.onError, tween(if (reducedMotion) 0 else 250), label = "theme-onError").value,
        errorContainer = animateColorAsState(colors.errorContainer, tween(if (reducedMotion) 0 else 250), label = "theme-errorContainer").value,
        onErrorContainer = animateColorAsState(colors.onErrorContainer, tween(if (reducedMotion) 0 else 250), label = "theme-onErrorContainer").value,
        outline = animateColorAsState(colors.outline, tween(if (reducedMotion) 0 else 250), label = "theme-outline").value,
        outlineVariant = animateColorAsState(colors.outlineVariant, tween(if (reducedMotion) 0 else 250), label = "theme-outlineVariant").value,
        scrim = animateColorAsState(colors.scrim, tween(if (reducedMotion) 0 else 250), label = "theme-scrim").value,
        surfaceBright = animateColorAsState(colors.surfaceBright, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceBright").value,
        surfaceDim = animateColorAsState(colors.surfaceDim, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceDim").value,
        surfaceContainer = animateColorAsState(colors.surfaceContainer, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceContainer").value,
        surfaceContainerHigh = animateColorAsState(colors.surfaceContainerHigh, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceContainerHigh").value,
        surfaceContainerHighest = animateColorAsState(colors.surfaceContainerHighest, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceContainerHighest").value,
        surfaceContainerLow = animateColorAsState(colors.surfaceContainerLow, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceContainerLow").value,
        surfaceContainerLowest = animateColorAsState(colors.surfaceContainerLowest, tween(if (reducedMotion) 0 else 250), label = "theme-surfaceContainerLowest").value
    )
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalReducedMotion provides reducedMotion,
        LocalSecondaryControlColors provides SecondaryControlColors(
            secondaryColors.primary,
            secondaryColors.onPrimary,
            secondaryColors.primaryContainer,
            secondaryColors.onPrimaryContainer,
        ),
    ) {
        MaterialExpressiveTheme(colorScheme = blended, typography = PlanTypography, shapes = PlanShapes, content = content)
    }
}
