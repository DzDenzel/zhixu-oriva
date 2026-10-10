package com.planlock.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionUiStateTest {
    @Test
    fun `state distinguishes first request retry permanent denial and granted`() {
        assertEquals(LocationPermissionUiState.ASK, locationPermissionUiState(false, false, false))
        assertEquals(LocationPermissionUiState.ASK_AGAIN, locationPermissionUiState(false, true, true))
        assertEquals(LocationPermissionUiState.APP_SETTINGS, locationPermissionUiState(false, true, false))
        assertEquals(LocationPermissionUiState.ENABLED, locationPermissionUiState(true, true, false))
    }
}
