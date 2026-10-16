package com.planlock.feature.settings

import com.planlock.app.api.AuthSession
import com.planlock.app.api.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMarksTest {
    @Test
    fun devicesOnlyManageableWhileSignedIn() {
        assertTrue(settingsDevicesManageable(AuthState(AuthSession("t", 1, 2, signedIn = true))))
        assertFalse(settingsDevicesManageable(AuthState(AuthSession())))
        assertFalse(settingsDevicesManageable(AuthState(AuthSession(guestUserId = 9), signedOut = true, dataOwner = 1)))
    }

    @Test
    fun everyOverviewItemHasADistinctGlyph() {
        val glyphs = SettingsOverviewItem.entries.map { settingsMark(it).glyph }
        assertEquals(glyphs.size, glyphs.toSet().size)
    }
}
