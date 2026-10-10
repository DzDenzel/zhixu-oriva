package com.planlock.app.engine

/** Immutable record of one complete exemption-assembly pass. */
class ExemptionAssemblySnapshot private constructor(
    val generation: Long,
    val facts: Map<String, PackageFacts>,
    val rolePackages: Set<String>,
    val basicToolsEnabled: Boolean,
    val basicToolPackages: Set<String>,
    val exemptPackages: Set<String>,
) {
    val classifiedPackages: Set<String> = facts.keys

    fun classify(packageName: String, packageFacts: PackageFacts): ExemptionClassification =
        ExemptionClassifier.classify(
            packageName = packageName,
            facts = packageFacts,
            roles = PackageRoles(isBasicRole = packageName in rolePackages),
            basicToolsEnabled = basicToolsEnabled,
            basicToolPackages = basicToolPackages,
        )

    /** Recomputes the expected exemption set from exactly the facts captured by this snapshot. */
    fun audit(): Set<String> = expectedExemptions(
        facts = facts,
        rolePackages = rolePackages,
        basicToolsEnabled = basicToolsEnabled,
        basicToolPackages = basicToolPackages,
    ).symmetricDifference(exemptPackages)

    companion object {
        fun assemble(
            generation: Long,
            facts: Map<String, PackageFacts>,
            rolePackages: Set<String>,
            basicToolsEnabled: Boolean,
            basicToolPackages: Set<String>,
        ): ExemptionAssemblySnapshot {
            val stableFacts = facts.toMap()
            val stableRoles = rolePackages.toSet()
            val stableBasicTools = basicToolPackages.toSet()
            return ExemptionAssemblySnapshot(
                generation = generation,
                facts = stableFacts,
                rolePackages = stableRoles,
                basicToolsEnabled = basicToolsEnabled,
                basicToolPackages = stableBasicTools,
                exemptPackages = expectedExemptions(
                    facts = stableFacts,
                    rolePackages = stableRoles,
                    basicToolsEnabled = basicToolsEnabled,
                    basicToolPackages = stableBasicTools,
                ),
            )
        }

        private fun expectedExemptions(
            facts: Map<String, PackageFacts>,
            rolePackages: Set<String>,
            basicToolsEnabled: Boolean,
            basicToolPackages: Set<String>,
        ): Set<String> = facts.filter { (packageName, packageFacts) ->
            ExemptionClassifier.classify(
                packageName = packageName,
                facts = packageFacts,
                roles = PackageRoles(isBasicRole = packageName in rolePackages),
                basicToolsEnabled = basicToolsEnabled,
                basicToolPackages = basicToolPackages,
            ) != ExemptionClassification.RESTRICTED
        }.keys + rolePackages + "com.planlock.app"
    }
}

private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)

data class EnginePackageDecision(
    val planId: Long,
    val snapshotGeneration: Long?,
    val classifiedInSnapshot: Boolean,
    val exemptInSnapshot: Boolean,
    val whitelisted: Boolean,
    val shouldTrack: Boolean,
)
