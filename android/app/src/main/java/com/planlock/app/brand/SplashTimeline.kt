package com.planlock.app.brand

import java.util.concurrent.atomic.AtomicBoolean

/** Claimed by the first Activity, not by services that may start the process earlier. */
internal class ColdSplashEntry {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

internal val processSplashEntry = ColdSplashEntry()

internal object SplashMotionTokens {
    const val durationMs = 1600L
    const val backgroundRevealMs = 360L
    const val wordmarkStartMs = 180L
    const val wordmarkEndMs = 580L
    const val exitStartMs = 1250L
    const val backdropOverscan = .06f
    const val wordmarkRiseDp = 12f
    const val wordmarkWidthDp = 224
    const val wordmarkHeightDp = 56
    const val wordmarkCenterOffsetDp = 64
}

internal data class SplashFrame(
    val backdropAlpha: Float,
    val backdropScale: Float,
    val wordmarkAlpha: Float,
    val wordmarkOffset: Float,
    val overlayAlpha: Float,
    val finished: Boolean,
)

/** Pure elapsed-time timeline: no network state and no accumulating frame drift. */
internal fun splashFrame(elapsedMs: Long, reducedMotion: Boolean): SplashFrame {
    val elapsed = if (reducedMotion) SplashMotionTokens.durationMs else elapsedMs.coerceAtLeast(0)
    fun progress(start: Long, end: Long): Float =
        ((elapsed - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    fun smooth(value: Float) = value * value * (3f - 2f * value)
    val travel = smooth(progress(0, SplashMotionTokens.durationMs))
    val wordmark = smooth(progress(SplashMotionTokens.wordmarkStartMs, SplashMotionTokens.wordmarkEndMs))
    return SplashFrame(
        backdropAlpha = smooth(progress(0, SplashMotionTokens.backgroundRevealMs)),
        backdropScale = 1f + SplashMotionTokens.backdropOverscan * (1f - travel),
        wordmarkAlpha = wordmark,
        wordmarkOffset = SplashMotionTokens.wordmarkRiseDp * (1f - wordmark),
        overlayAlpha = 1f - smooth(progress(SplashMotionTokens.exitStartMs, SplashMotionTokens.durationMs)),
        finished = elapsed >= SplashMotionTokens.durationMs,
    )
}
