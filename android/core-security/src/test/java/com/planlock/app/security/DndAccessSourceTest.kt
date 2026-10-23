package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class DndAccessSourceTest {
    @Test fun provenanceDoesNotTurnEffectiveSystemAccessIntoMissingPermission() {
        assertEquals(DndAccessSource.NONE, dndAccessSource(false, false, true))
        assertEquals(DndAccessSource.NOTIFICATION_LISTENER, dndAccessSource(true, false, true))
        assertEquals(DndAccessSource.DIRECT, dndAccessSource(true, true, true))
        assertEquals(DndAccessSource.DIRECT, dndAccessSource(true, true, false))
    }
}
