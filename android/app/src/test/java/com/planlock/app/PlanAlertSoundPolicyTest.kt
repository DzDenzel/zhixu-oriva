package com.planlock.app

import org.junit.Assert.*
import org.junit.Test

class PlanAlertSoundPolicyTest {
    @Test fun silentNeverSoundsOrVibrates() {
        for (filter in 1..4) for (owned in listOf(false, true)) {
            assertFalse(PlanAlertSoundPolicy.sound(0, filter, owned))
            assertFalse(PlanAlertSoundPolicy.vibrate(0, filter, owned))
        }
    }
    @Test fun vibrationModeOnlyVibratesOutsideUserDnd() {
        assertFalse(PlanAlertSoundPolicy.sound(1, 1, false))
        assertTrue(PlanAlertSoundPolicy.vibrate(1, 1, false))
        assertFalse(PlanAlertSoundPolicy.vibrate(1, 3, false))
        assertTrue(PlanAlertSoundPolicy.vibrate(1, 4, true))
    }
    @Test fun userDndAndUnverifiedReleaseAreSilent() {
        for (filter in 2..4) assertFalse(PlanAlertSoundPolicy.sound(2, filter, false))
        assertFalse(PlanAlertSoundPolicy.sound(2, 1, true, verified = false))
        assertFalse(PlanAlertSoundPolicy.vibrate(2, 1, true, verified = false))
    }
    @Test fun ownDndAllowsAlarmAndNormalModeAllowsCompletion() {
        assertTrue(PlanAlertSoundPolicy.sound(2, 4, true))
        assertTrue(PlanAlertSoundPolicy.sound(2, 1, false))
    }
    @Test fun snoozeHasThreeDurableSlots() {
        (0..2).forEach { assertTrue(PlanAlertSoundPolicy.maySnooze(it)) }
        assertFalse(PlanAlertSoundPolicy.maySnooze(3))
        assertFalse(PlanAlertSoundPolicy.maySnooze(4))
    }
}
