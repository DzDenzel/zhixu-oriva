package com.planlock.app.brand

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.planlock.app.R
import com.planlock.design.LocalDarkTheme
import com.planlock.design.LocalReducedMotion
import com.planlock.design.V2Tokens

/** The only image-source seam. Motion and timing do not know the resource format. */
@Composable
internal fun SplashBackdrop(dark: Boolean, modifier: Modifier = Modifier) {
    Image(
        painterResource(if (dark) R.drawable.bg_splash_dark else R.drawable.bg_splash_light),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
internal fun BrandSplash(systemSplashGone: Boolean, onFinished: () -> Unit) {
    val dark = LocalDarkTheme.current
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    var elapsed by remember { mutableLongStateOf(0L) }
    val frame = splashFrame(elapsed, reduced)
    LaunchedEffect(reduced, systemSplashGone) {
        val started = System.nanoTime()
        if (!reduced) {
            if (!systemSplashGone) return@LaunchedEffect
            // LaunchedEffect starts after composition; wait for the platform exit,
            // but never make completion depend on another rendered frame.
            android.util.Log.i("OrivaSplash", "playback-start")
            playSplash { elapsed = it }
        }
        android.util.Log.i("OrivaSplash", "playback-finished reduced=$reduced elapsedMs=${(System.nanoTime() - started) / 1_000_000L}")
        finish()
    }
    // Reduce-motion exits immediately; no invisible overlay intercepts the first tap.
    if (frame.finished) return
    BackHandler { finish() }
    val palette = if (dark) V2Tokens.dark else V2Tokens.light
    // Splash-specific ink from approved study A; do not change global brand tokens.
    val ink = Color(if (dark) 0xFFD7E1D8 else 0xFF38503F)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val mottoFont = remember { FontFamily(Font(R.font.zhixu_motto, FontWeight.Normal)) }
    // The app's explicit theme may differ from the system theme; resolve only
    // this gradient mark in the correct night palette, including manual overrides.
    val markContext = remember(context, configuration, dark) {
        context.createConfigurationContext(Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
    }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .graphicsLayer { alpha = frame.overlayAlpha }
            .background(palette.surface)
            .semantics { contentDescription = "知序品牌开屏" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SplashBackdrop(dark, Modifier.graphicsLayer {
            alpha = frame.backdropAlpha
            scaleX = frame.backdropScale
            scaleY = frame.backdropScale
        })
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Reuse the existing reveal progress to settle from the system mark's
            // center into the approved 46%-height lockup; no new timing or deadline.
            val settledOffset = -maxHeight * 0.04f
            Column(
                Modifier.padding(horizontal = 32.dp).graphicsLayer {
                    // Use the measured lockup, not one assumed text line: narrow
                    // screens and large fonts can wrap before the reveal starts.
                    val initialOffset = (size.height - 66.dp.toPx()) / 2f
                    translationY = initialOffset * (1f - frame.wordmarkAlpha) +
                        settledOffset.toPx() * frame.wordmarkAlpha
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(
                    LocalContext provides markContext,
                    LocalConfiguration provides markContext.resources.configuration,
                ) {
                    Image(
                        painterResource(R.drawable.zhixu_splash_core), null,
                        modifier = Modifier.size(width = 58.dp, height = 66.dp).graphicsLayer {
                            val markScale = 52f / 66f + (1f - 52f / 66f) * frame.wordmarkAlpha
                            scaleX = markScale
                            scaleY = markScale
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.brand_motto),
                    style = TextStyle(
                        fontFamily = mottoFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 20.sp,
                        lineHeight = 32.sp,
                        letterSpacing = 2.5.sp,
                        lineBreak = LineBreak.Heading,
                    ),
                    textAlign = TextAlign.Center,
                    color = ink,
                    modifier = Modifier.graphicsLayer {
                        alpha = frame.wordmarkAlpha
                        translationY = frame.wordmarkOffset.dp.toPx()
                    },
                )
            }
        }
    }
}
