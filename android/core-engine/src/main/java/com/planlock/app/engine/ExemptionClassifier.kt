package com.planlock.app.engine

data class PackageFacts(val uid: Int, val systemFlag: Boolean, val hasLauncherEntry: Boolean)

data class PackageRoles(
    val isHome: Boolean = false,
    val isIme: Boolean = false,
    val isSystemUi: Boolean = false,
    val isBasicRole: Boolean = false,
)

enum class ExemptionClassification { SYSTEM_CORE, BASIC_TOOL, USER_ALLOWED, RESTRICTED }

/** ADR-006：纯 Kotlin 判定；基础清单由调用方注入，避免 core-engine 依赖 core-api。 */
object ExemptionClassifier {
    fun classify(
        packageName: String,
        facts: PackageFacts,
        roles: PackageRoles = PackageRoles(),
        basicToolsEnabled: Boolean = true,
        basicToolPackages: Set<String> = emptySet(),
        whitelistPackages: Set<String> = emptySet(),
    ): ExemptionClassification = when {
        packageName == "com.planlock.app" -> ExemptionClassification.USER_ALLOWED
        facts.uid < 10000 -> ExemptionClassification.SYSTEM_CORE
        facts.systemFlag && !facts.hasLauncherEntry -> ExemptionClassification.SYSTEM_CORE
        roles.isHome || roles.isIme || roles.isSystemUi || roles.isBasicRole -> ExemptionClassification.SYSTEM_CORE
        basicToolsEnabled && packageName in basicToolPackages -> ExemptionClassification.BASIC_TOOL
        packageName in whitelistPackages -> ExemptionClassification.USER_ALLOWED
        else -> ExemptionClassification.RESTRICTED
    }
}
