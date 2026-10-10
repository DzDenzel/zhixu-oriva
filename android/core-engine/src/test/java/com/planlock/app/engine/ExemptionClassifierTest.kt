package com.planlock.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ExemptionClassifierTest {
    private val ordinary = PackageFacts(uid = 10000, systemFlag = false, hasLauncherEntry = true)

    @Test
    fun systemUidBoundaryDoesNotExemptRegularAppUid() {
        assertEquals(ExemptionClassification.SYSTEM_CORE, classify(ordinary.copy(uid = 9999)))
        assertEquals(ExemptionClassification.RESTRICTED, classify(ordinary))
    }

    @Test
    fun onlySystemPackagesWithoutLauncherAreCoreComponents() {
        assertEquals(ExemptionClassification.SYSTEM_CORE, classify(ordinary.copy(systemFlag = true, hasLauncherEntry = false)))
        assertEquals(ExemptionClassification.RESTRICTED, classify(ordinary.copy(systemFlag = true)))
        assertEquals(ExemptionClassification.RESTRICTED, classify(ordinary.copy(hasLauncherEntry = false)))
    }

    @Test
    fun eachEssentialRoleSurvivesBasicToolsBeingDisabled() {
        listOf(
            PackageRoles(isHome = true),
            PackageRoles(isIme = true),
            PackageRoles(isSystemUi = true),
            PackageRoles(isBasicRole = true),
        ).forEach { roles ->
            assertEquals(ExemptionClassification.SYSTEM_CORE, classify(ordinary, roles))
        }
    }

    @Test
    fun basicToolToggleControlsListedToolsOnly() {
        assertEquals(ExemptionClassification.BASIC_TOOL, ExemptionClassifier.classify(
            "calculator", ordinary, basicToolsEnabled = true, basicToolPackages = setOf("calculator"),
        ))
        assertEquals(ExemptionClassification.RESTRICTED, ExemptionClassifier.classify(
            "calculator", ordinary, basicToolsEnabled = false, basicToolPackages = setOf("calculator"),
        ))
        assertEquals(ExemptionClassification.RESTRICTED, ExemptionClassifier.classify(
            "unlisted", ordinary, basicToolsEnabled = true, basicToolPackages = setOf("calculator"),
        ))
    }

    @Test
    fun explicitlyAllowedAppSurvivesDisabledBasicTools() {
        assertEquals(ExemptionClassification.USER_ALLOWED, ExemptionClassifier.classify(
            "calculator", ordinary, basicToolsEnabled = false,
            basicToolPackages = setOf("calculator"), whitelistPackages = setOf("calculator"),
        ))
    }

    @Test
    fun planLockAlwaysClassifiesAsUserAllowedIncludingSystemUid() {
        assertEquals(ExemptionClassification.USER_ALLOWED, ExemptionClassifier.classify(
            "com.planlock.app", ordinary.copy(uid = 9999), basicToolsEnabled = false,
        ))
    }

    private fun classify(facts: PackageFacts, roles: PackageRoles = PackageRoles()) =
        ExemptionClassifier.classify("example.app", facts, roles, basicToolsEnabled = false)
}
