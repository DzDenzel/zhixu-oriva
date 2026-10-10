package com.planlock.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExemptionAssemblySnapshotTest {
    private val ordinary = PackageFacts(uid = 10000, systemFlag = false, hasLauncherEntry = true)

    @Test
    fun assemblyMatchesClassifierForTheSameFactsAndAddsRoleAndOwnPackage() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 7,
            facts = mapOf(
                "system.uid" to ordinary.copy(uid = 9999),
                "system.hidden" to ordinary.copy(systemFlag = true, hasLauncherEntry = false),
                "ordinary" to ordinary,
                "basic" to ordinary,
                "role" to ordinary,
            ),
            rolePackages = setOf("role"),
            basicToolsEnabled = true,
            basicToolPackages = setOf("basic"),
        )

        assertEquals(7L, snapshot.generation)
        assertEquals(
            setOf("system.uid", "system.hidden", "basic", "role", "com.planlock.app"),
            snapshot.exemptPackages,
        )
        assertTrue(snapshot.audit().isEmpty())
    }

    @Test
    fun disablingBasicToolsDoesNotRemoveSystemOrRoleExemptions() {
        val snapshot = ExemptionAssemblySnapshot.assemble(
            generation = 8,
            facts = mapOf("system" to ordinary.copy(uid = 1000), "basic" to ordinary, "role" to ordinary),
            rolePackages = setOf("role"),
            basicToolsEnabled = false,
            basicToolPackages = setOf("basic"),
        )

        assertEquals(setOf("system", "role", "com.planlock.app"), snapshot.exemptPackages)
    }
}
