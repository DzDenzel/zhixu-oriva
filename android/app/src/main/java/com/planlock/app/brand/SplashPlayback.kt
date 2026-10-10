package com.planlock.app.brand

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Frames present the timeline; a frame-independent deadline completes it. */
internal suspend fun playSplash(onElapsed: (Long) -> Unit): Unit = coroutineScope {
    val start = System.nanoTime()
    val frames = launch {
        while (isActive) {
            withFrameNanos {
                onElapsed(((System.nanoTime() - start) / 1_000_000L).coerceIn(0L, SplashMotionTokens.durationMs))
            }
        }
    }
    try {
        // Do not await even the first frame here: that wait can also be starved.
        delay(SplashMotionTokens.durationMs)
        onElapsed(SplashMotionTokens.durationMs)
    } finally {
        frames.cancel()
    }
}
