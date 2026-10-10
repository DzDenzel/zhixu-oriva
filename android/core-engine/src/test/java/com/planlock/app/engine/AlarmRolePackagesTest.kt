package com.planlock.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmRolePackagesTest {
    @Test
    fun resolvedClockAndKnownFallbacksRemainEssentialWhenBasicToolsAreDisabled() {
        val roles = essentialAlarmPackages("vendor.clock")

        assertEquals(
            setOf("vendor.clock", "com.android.deskclock", "com.google.android.deskclock", "com.coloros.alarmclock"),
            roles,
        )
    }
}
