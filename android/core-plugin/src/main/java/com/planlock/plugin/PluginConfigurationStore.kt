package com.planlock.plugin

import android.content.Context

/** User/plan/plugin namespaced host storage. Values never grant additional capabilities. */
class PluginConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plugin_configuration", Context.MODE_PRIVATE)
    private fun prefix(userId: Long?, planId: Long, pluginId: String) = "$userId:$planId:${pluginId.length}:$pluginId:"
    private fun identityKey(userId: Long?, planId: Long) = "plan-identity:$userId:$planId"

    private fun namespace(scope: String) = "scope:${scope.length}:$scope:"
    private fun identityKey(scope: String, planId: Long) = "plan-identity:${namespace(scope)}$planId"

    /** The startup session supplies frozen pre-upgrade provenance, never the next login. */
    fun registerLegacyOwner(scope: String?) = synchronized(namespaceLock) {
        if (!preferences.contains(LEGACY_OWNER)) {
            check(preferences.edit().putString(LEGACY_OWNER, scope.orEmpty()).commit()) { "Plugin legacy owner could not be persisted" }
        }
    }

    private fun migrateLegacy(scope: String) {
        val owner = when {
            scope == LOCAL_SCOPE -> "null"
            scope == preferences.getString(LEGACY_OWNER, null) -> scope.substringAfterLast('\n').toLongOrNull()?.toString()
            else -> null
        } ?: return // Ambiguous old user keys remain intact for explicit recovery.
        val marker = "legacy-migrated:${namespace(scope)}"
        if (preferences.getBoolean(marker, false)) return
        val oldPrefix = "$owner:"
        val oldIdentity = "plan-identity:$owner:"
        val current = preferences.all
        val edit = preferences.edit()
        current.forEach { (key, value) ->
            val target = when {
                key.startsWith(oldPrefix) -> namespace(scope) + key.removePrefix(oldPrefix)
                key.startsWith(oldIdentity) -> "plan-identity:${namespace(scope)}" + key.removePrefix(oldIdentity)
                else -> return@forEach
            }
            if (!current.containsKey(target)) when (value) {
                is String -> edit.putString(target, value)
                is Long -> edit.putLong(target, value)
            }
            edit.remove(key)
        }
        check(edit.putBoolean(marker, true).commit()) { "Plugin legacy configuration could not be migrated" }
    }

    fun canonicalPlanId(scope: String, planId: Long): Long = synchronized(namespaceLock) {
        migrateLegacy(scope)
        if (planId > 0) planId else preferences.getLong(identityKey(scope, planId), planId).also {
            check(it == planId || it > 0) { "Plugin identity must be canonical" }
        }
    }

    fun values(scope: String, planId: Long, pluginId: String, fields: List<PluginFormField>): Map<String, String> = synchronized(namespaceLock) {
        val canonical = canonicalPlanId(scope, planId)
        fields.associate { field -> field.id to (preferences.getString("${namespace(scope)}$canonical:${pluginId.length}:$pluginId:${field.id}", null)
            ?.takeIf { validFormValue(field, it) } ?: field.value.content) }
    }

    fun save(scope: String, planId: Long, pluginId: String, field: PluginFormField, value: String) = synchronized(namespaceLock) {
        require(validFormValue(field, value))
        val canonical = canonicalPlanId(scope, planId)
        preferences.edit().putString("${namespace(scope)}$canonical:${pluginId.length}:$pluginId:${field.id}", value).apply()
    }

    fun movePlan(scope: String, from: Long, to: Long) { movePlanDurably(scope, from, to) }

    fun movePlanDurably(scope: String, from: Long, to: Long, adoptUnownedDraft: Boolean = false) = synchronized(namespaceLock) {
        if (from == to) return@synchronized
        if (adoptUnownedDraft) require(scope != LOCAL_SCOPE && from < 0 && to > 0)
        val canonical = canonicalPlanId(scope, to)
        val prior = canonicalPlanId(scope, from)
        require(prior == from || prior == canonical) { "Conflicting plugin identity" }
        if (adoptUnownedDraft) migrateLegacy(LOCAL_SCOPE)
        val starts = listOfNotNull("${namespace(scope)}$from:", "${namespace(LOCAL_SCOPE)}$from:".takeIf { adoptUnownedDraft }).distinct()
        val current = preferences.all
        val destinations = current.keys.toMutableSet()
        val edit = preferences.edit()
        starts.forEach { start ->
            current.filterKeys { it.startsWith(start) }.forEach { (key, value) ->
                val target = "${namespace(scope)}$canonical:" + key.removePrefix(start)
                if (value is String && destinations.add(target)) edit.putString(target, value)
                edit.remove(key)
            }
        }
        if (from < 0 && canonical > 0) edit.putLong(identityKey(scope, from), canonical)
        check(edit.commit()) { "Plugin configuration mapping could not be persisted" }
    }

    /** Successful guest merges transfer form values and aliases together; destination values win. */
    fun moveScope(source: String, destination: String) = synchronized(namespaceLock) {
        if (source == destination) return@synchronized
        migrateLegacy(source)
        migrateLegacy(destination)
        val current = preferences.all
        val edit = preferences.edit()
        current.forEach { (key, value) ->
            val target = when {
                key.startsWith(namespace(source)) -> namespace(destination) + key.removePrefix(namespace(source))
                key.startsWith("plan-identity:${namespace(source)}") -> "plan-identity:${namespace(destination)}" + key.removePrefix("plan-identity:${namespace(source)}")
                else -> return@forEach
            }
            if (!current.containsKey(target)) when (value) {
                is String -> edit.putString(target, value)
                is Long -> edit.putLong(target, value)
            }
            edit.remove(key)
        }
        check(edit.commit()) { "Plugin guest configuration could not be transferred" }
    }

    /** Same-owner aliases survive an open editor and asynchronous actions across mapping/restart. */
    fun canonicalPlanId(userId: Long?, planId: Long): Long = synchronized(namespaceLock) {
        if (planId > 0) planId else preferences.getLong(identityKey(userId, planId), planId).also {
            check(it == planId || it > 0) { "Plugin identity must be canonical" }
        }
    }

    fun values(userId: Long?, planId: Long, pluginId: String, fields: List<PluginFormField>): Map<String, String> = synchronized(namespaceLock) {
        val canonical = canonicalPlanId(userId, planId)
        fields.associate { field -> field.id to (preferences.getString(prefix(userId, canonical, pluginId) + field.id, null)
            ?.takeIf { validFormValue(field, it) } ?: field.value.content) }
    }

    fun save(userId: Long?, planId: Long, pluginId: String, field: PluginFormField, value: String) = synchronized(namespaceLock) {
        require(validFormValue(field, value))
        // A new same-owner edit is authoritative, even if its action began before mapping.
        preferences.edit().putString(prefix(userId, canonicalPlanId(userId, planId), pluginId) + field.id, value).apply()
    }

    fun movePlan(userId: Long?, from: Long, to: Long) { movePlanDurably(userId, from, to) }

    /** Commit both fallback adoption and the same-owner alias before Room acknowledges mapping. */
    fun movePlanDurably(userId: Long?, from: Long, to: Long, adoptUnownedDraft: Boolean = false) = synchronized(namespaceLock) {
        if (from == to) return@synchronized
        if (adoptUnownedDraft) require(userId != null && from < 0 && to > 0)
        val canonical = canonicalPlanId(userId, to)
        val prior = canonicalPlanId(userId, from)
        require(prior == from || prior == canonical) { "Conflicting plugin identity" }
        val starts = listOfNotNull("$userId:$from:", "null:$from:".takeIf { adoptUnownedDraft }).distinct()
        val existing = preferences.all
        val destinations = existing.keys.toMutableSet()
        val edit = preferences.edit()
        starts.forEach { start ->
            existing.filterKeys { it.startsWith(start) }.forEach { (key, value) ->
                val target = "$userId:$canonical:" + key.removePrefix(start)
                // Adoption is fallback data. Existing owned values always have priority.
                if (value is String && destinations.add(target)) edit.putString(target, value)
                edit.remove(key)
            }
        }
        if (from < 0 && canonical > 0) edit.putLong(identityKey(userId, from), canonical)
        check(edit.commit()) { "Plugin configuration mapping could not be persisted" }
    }

    companion object {
        private const val LEGACY_OWNER = "legacy-owner-scope-v1"
        private const val LOCAL_SCOPE = "local\nlocal"
        // All instances use the same SharedPreferences file; migration and delayed writes serialize.
        private val namespaceLock = Any()
    }
}
