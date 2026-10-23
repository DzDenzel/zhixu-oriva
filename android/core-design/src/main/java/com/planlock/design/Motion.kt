@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.planlock.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalSharedVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
val LocalDarkTheme = staticCompositionLocalOf { false }
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun Modifier.sharedPlanElement(key: String?, status: Boolean = false, duration: Int = 280): Modifier {
    val shared = LocalSharedScope.current
    val visibility = LocalSharedVisibility.current
    if (key == null || shared == null || visibility == null || LocalReducedMotion.current) return this
    return with(shared) {
        if (status) sharedBounds(rememberSharedContentState(key), visibility,
            boundsTransform = { _, _ -> tween(duration) })
        else sharedElement(rememberSharedContentState(key), visibility,
            boundsTransform = { _, _ -> tween(duration) })
    }
}


/**
 * 按压反馈（v4.2，impeccable animate「acknowledge an action」）：卡片按下时整体轻缩到 97.5%（100ms），
 * 松手用弹簧回到原位；系统减弱动画时不缩放，只保留水波纹。
 * 用法：缩放加在 clip / background 之前，点击加在之后，两者共用同一个 [InkPress]。
 */
class InkPress internal constructor(
    val interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
    internal val scale: androidx.compose.runtime.State<Float>,
)

@Composable
fun rememberInkPress(): InkPress {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed.value && !reduced) .975f else 1f,
        animationSpec = if (pressed.value) tween(100) else androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 700f),
        label = "ink-press",
    )
    return remember(source) { InkPress(source, scale) }
}

fun Modifier.inkPressScale(press: InkPress): Modifier = graphicsLayer {
    val s = press.scale.value
    scaleX = s
    scaleY = s
}

@Composable
fun Modifier.inkPressClick(press: InkPress, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = press.interactionSource,
        indication = ripple(),
        enabled = enabled,
        onClick = onClick,
    )
