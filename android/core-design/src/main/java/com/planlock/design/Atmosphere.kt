package com.planlock.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** 页面共享同一材质语言，但以不同叙事背景区分场景。 */
enum class PageAtmosphere(@DrawableRes internal val drawable: Int?) {
    TODAY(R.drawable.bg_today_timeflow),
    PLANS(R.drawable.bg_plans_path),
    ASSISTANT(R.drawable.bg_assistant_prism),
    STATS(R.drawable.bg_stats_rhythm),
    QUIET(null),
}

@Composable
fun Modifier.planLockAtmosphere(style: PageAtmosphere = PageAtmosphere.QUIET): Modifier {
    val dark = LocalDarkTheme.current
    val palette = if (dark) V2Tokens.dark else V2Tokens.light
    val base = background(Brush.verticalGradient(listOf(palette.surface, palette.surfaceLow)))
    val image = style.drawable ?: return base.background(
        Brush.radialGradient(
            listOf(atmosphereAccent(style).copy(alpha = if (dark) .08f else .055f), Color.Transparent),
        )
    )
    val alpha = when (style) {
        PageAtmosphere.STATS -> if (dark) .36f else .18f
        else -> if (dark) .16f else .34f
    }
    return base.paint(
        painter = painterResource(image),
        contentScale = ContentScale.Crop,
        alpha = alpha,
    )
}

private fun atmosphereAccent(style: PageAtmosphere): Color = when (style) {
    PageAtmosphere.PLANS -> Color(0xFF85936C)
    PageAtmosphere.ASSISTANT -> Color(0xFF8E89B8)
    PageAtmosphere.STATS -> Color(0xFF315F58)
    else -> V2Tokens.brandBright
}
