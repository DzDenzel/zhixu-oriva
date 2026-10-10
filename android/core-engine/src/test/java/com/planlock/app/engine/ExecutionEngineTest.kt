package com.planlock.app.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExecutionEngineTest {
    @Before fun reset() = ExecutionEngine.stop()
    @After fun cleanUp() = ExecutionEngine.stop()

    @Test
    fun activePlanTracksOnlyNonExemptAndNonWhitelistedApps() {
        ExecutionEngine.start(
            planId = 7,
            whitelistPackages = setOf("allowed"),
            exemptPackages = setOf("system"),
            classifiedPackages = setOf("allowed", "system", "other"),
            exemptionSnapshotGeneration = 12,
        )

        assertFalse(ExecutionEngine.shouldTrack("allowed"))
        assertFalse(ExecutionEngine.shouldTrack("system"))
        assertTrue(ExecutionEngine.shouldTrack("other"))
        assertEquals(
            EnginePackageDecision(
                planId = 7,
                snapshotGeneration = 12,
                classifiedInSnapshot = true,
                exemptInSnapshot = true,
                whitelisted = false,
                shouldTrack = false,
            ),
            ExecutionEngine.inspect("system"),
        )
    }

    @Test
    fun ownPackageMissingForegroundAndInactivePlanAreNeverTracked() {
        ExecutionEngine.start(7, emptySet(), emptySet())
        assertFalse(ExecutionEngine.shouldTrack("com.planlock.app"))
        assertFalse(ExecutionEngine.shouldTrack(null))
        assertFalse(ExecutionEngine.shouldTrack(" "))
        ExecutionEngine.stop()
        assertFalse(ExecutionEngine.shouldTrack("other"))
        assertNull(ExecutionEngine.inspect("other"))
    }

    @Test
    fun stateVersionChangesOnlyWhenObservationStateChanges() {
        val before = ExecutionEngine.stateVersion.value
        ExecutionEngine.start(9, setOf("allowed"), setOf("system"))
        val afterStart = ExecutionEngine.stateVersion.value
        ExecutionEngine.start(9, setOf("allowed"), setOf("system"))
        assertEquals(afterStart, ExecutionEngine.stateVersion.value)
        ExecutionEngine.stop()

        assertTrue(afterStart > before)
        assertTrue(ExecutionEngine.stateVersion.value > afterStart)
    }
}
