package com.planlock.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionKindTest {
    @Test
    fun `missing base requirements exclude plugin permission and optional dnd`() {
        val missing = PermissionKind.entries.filter { it.requiredForExecution }.toSet()
        assertEquals(setOf(PermissionKind.USAGE_STATS, PermissionKind.NOTIFICATIONS), missing)
    }

    @Test
    fun `location is the fifth optional runtime capability after device admin retirement`() {
        assertEquals(5, PermissionKind.entries.size)
        assertTrue(PermissionKind.LOCATION.runtime)
        assertFalse(PermissionKind.LOCATION.requiredForExecution)
        assertTrue(PermissionKind.entries.filterNot { it == PermissionKind.LOCATION }.all { !it.runtime })
    }
}
