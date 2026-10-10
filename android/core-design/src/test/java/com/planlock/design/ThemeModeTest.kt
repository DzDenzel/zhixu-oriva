package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun manualModesOverrideSystemAndStoredValuesRoundTrip() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
            assertEquals(false, ThemeMode.LIGHT.isDark(systemDark))
            assertEquals(true, ThemeMode.DARK.isDark(systemDark))
        }
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromValue(mode.value))
        for (value in listOf(null, "", "unknown")) assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue(value))
    }
}
