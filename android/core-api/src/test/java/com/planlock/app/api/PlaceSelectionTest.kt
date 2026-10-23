package com.planlock.app.api

import org.junit.Assert.*
import org.junit.Test

class PlaceSelectionTest {
    @Test fun legacyTextDoesNotRequireCoordinatesButMapPointsAreFiniteAndPaired() {
        assertTrue(validCoordinates(null,null))
        assertTrue(validCoordinates(31.23,121.47))
        assertFalse(validCoordinates(31.23,null))
        assertFalse(validCoordinates(Double.NaN,121.47))
        assertFalse(validCoordinates(0.0,181.0))
        assertFalse(validCoordinates(-91.0,0.0))
    }
}
