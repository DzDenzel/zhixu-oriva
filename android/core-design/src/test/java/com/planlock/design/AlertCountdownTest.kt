package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertCountdownTest {
    @Test fun roundsUpToTheNextSecondAndNeverGoesNegative() {
        assertEquals("05:42", alertCountdown(341_001))
        assertEquals("05:42", alertCountdown(342_000))
        assertEquals("00:01", alertCountdown(1))
        assertEquals("00:00", alertCountdown(0))
        assertEquals("00:00", alertCountdown(-5_000))
    }

    @Test fun switchesToHoursFromSixtyMinutes() {
        assertEquals("59:59", alertCountdown(3_599_000))
        assertEquals("1:00:00", alertCountdown(3_600_000))
        assertEquals("2:05:09", alertCountdown(7_509_000))
    }
}
