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

