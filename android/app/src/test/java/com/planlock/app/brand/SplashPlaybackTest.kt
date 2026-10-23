package com.planlock.app.brand

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SplashPlaybackTest {
    // Real Compose clock boundary, with no artificial finish signal.
    private class StoppedClock(private val firstFrame: Boolean) : MonotonicFrameClock {
        var requests = 0
        var cancelled = false
        val waiting = CompletableDeferred<Unit>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            requests++
            if (firstFrame && requests == 1) return onFrame(System.nanoTime())
            waiting.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
    }

    @Test fun completesWhenNoFirstFrameEverArrives() = runBlocking {
        val clock = StoppedClock(false)
        var elapsed = -1L
        var finished = 0
        withTimeout(3000) {
            withContext(clock) { playSplash { elapsed = it }; finished++ }
        }
        assertEquals(1, finished)
        assertEquals(1600L, elapsed)
        assertTrue(clock.cancelled)
    }

    @Test fun completesWhenFramesStopAfterTheFirstFrame() = runBlocking {
        val clock = StoppedClock(true)
        var finished = 0
        withTimeout(3000) { withContext(clock) { playSplash { }; finished++ } }
        assertEquals(1, finished)
        assertTrue(clock.requests >= 2)
        assertTrue(clock.cancelled)
    }

    @Test fun healthyFramesPresentTheTimelineWithoutFinishingEarly() = runBlocking {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(System.nanoTime())
            }
        }
        val observed = mutableListOf<Long>()
        val started = System.nanoTime()
        withTimeout(3000) { withContext(clock) { playSplash { observed += it } } }
        assertTrue("must not shorten the 1600ms presentation", (System.nanoTime() - started) / 1_000_000 >= 1600L)
        assertTrue(observed.any { it in 180L..979L })
        assertEquals(1600L, observed.last())
        assertTrue(splashFrame(observed.last(), false).finished)
    }

    @Test fun completesWhenFramesStopWhileTheWordmarkIsVisible() = runBlocking {
        val started = System.nanoTime()
        var cancelled = false
        var lastPresented = -1L
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                if (lastPresented >= 350L) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                delay(16)
                return onFrame(System.nanoTime())
            }
        }
        val observed = mutableListOf<Long>()
        withTimeout(3000) {
            withContext(clock) {
                playSplash { lastPresented = it; observed += it }
            }
        }
        assertTrue("frames must stop within the wordmark phase", observed.dropLast(1).last() in 180L..579L)
        assertEquals(1600L, observed.last())
        assertTrue((System.nanoTime() - started) / 1_000_000L >= 1600L)
        assertTrue("deadline cancels the stalled frame request", cancelled)
    }

    @Test fun cancellationDoesNotRunALateCompletion() = runBlocking {
        val clock = StoppedClock(false)
        var finished = false
        val job = launch(clock) { playSplash { }; finished = true }
        withTimeout(3000) { clock.waiting.await() }
        job.cancelAndJoin()
        assertFalse(finished)
        assertTrue(clock.cancelled)
    }
}
