package com.planlock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanEditRouteTest {
    @Test
    fun `offline plan route retains its id for loading and updating the original plan`() {
        assertEquals(-1791395830820L, planIdForEdit(-1791395830820L))
    }

    @Test
    fun `only the existing new plan sentinel and absent id mean creation`() {
        assertNull(planIdForEdit(-1L))
        assertNull(planIdForEdit(null))
    }

    @Test
    fun `server plan route retains its existing editing identity`() {
        assertEquals(42L, planIdForEdit(42L))
    }
}
