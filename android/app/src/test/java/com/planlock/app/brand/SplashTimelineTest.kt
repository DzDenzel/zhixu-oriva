package com.planlock.app.brand

import org.junit.Assert.*
import org.junit.Test

class SplashTimelineTest {
    @Test fun coldEntryIsClaimedOnlyOnceEvenWhenActivityIsRecreated() {
        val entry = ColdSplashEntry()
        assertTrue(entry.claim())
        assertFalse(entry.claim())
        assertFalse(entry.claim())
    }

    @Test fun newProcessCanShowBrandAgain() {
        assertTrue(ColdSplashEntry().claim())
        assertTrue(ColdSplashEntry().claim())
    }

    @Test fun backgroundOpensBeforeWordmarkAndAnimationFinishesWithoutNetworkInput() {
        val first = splashFrame(0, false)
        val middle = splashFrame(600, false)
        val last = splashFrame(1200, false)
        assertEquals(0f, first.wordmarkAlpha, 0f)
        assertTrue(middle.wordmarkAlpha > 0.9f)
        assertTrue(middle.backdropScale < first.backdropScale)
        assertTrue(middle.backdropScale >= 1f)
        assertEquals(1f, middle.overlayAlpha, 0f)
        assertTrue(last.finished)
        assertEquals(0f, last.overlayAlpha, 0f)
    }

    @Test fun reducedMotionImmediatelyFinishesIncludingWhenEnabledMidAnimation() {
        for (elapsed in listOf(0L, 320L, 700L)) {
            val frame = splashFrame(elapsed, true)
            assertTrue(frame.finished)
            assertEquals(1f, frame.backdropScale, 0f)
            assertEquals(0f, frame.wordmarkOffset, 0f)
        }
    }

    @Test fun delayedOrNegativeFramesNeverOvershootOrRestart() {
        assertEquals(splashFrame(0, false), splashFrame(-10, false))
        assertTrue(splashFrame(50_000, false).finished)
        for (elapsed in 0L..1400L step 20) {
            val frame = splashFrame(elapsed, false)
            assertTrue(frame.overlayAlpha in 0f..1f)
            assertTrue(frame.wordmarkAlpha in 0f..1f)
            assertTrue(frame.backdropScale >= 1f)
        }
    }
}
