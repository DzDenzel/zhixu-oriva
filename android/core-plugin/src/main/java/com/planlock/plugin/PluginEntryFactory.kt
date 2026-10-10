package com.planlock.plugin

import java.io.File

/** Optional plugin-owned state directory; old entries keep their no-argument constructor. */
internal fun instantiatePluginEntry(entry: Class<*>, stateDirectory: File): PlanLockPlugin {
    val fileConstructor = entry.constructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(File::class.java)) }
    val instance = fileConstructor?.newInstance(stateDirectory) ?: entry.getDeclaredConstructor().newInstance()
    require(instance is PlanLockPlugin) { "Entry point does not implement PlanLockPlugin" }
    return instance
}
