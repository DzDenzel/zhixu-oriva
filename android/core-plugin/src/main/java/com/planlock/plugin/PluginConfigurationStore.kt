package com.planlock.plugin

import android.content.Context

/** User/plan/plugin namespaced host storage. Values never grant additional capabilities. */
class PluginConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("plugin_configuration", Context.MODE_PRIVATE)
    private fun prefix(userId: Long?, planId: Long, pluginId: String) = "$userId:$planId:${pluginId.length}:$pluginId:"
    private fun identityKey(userId: Long?, planId: Long) = "plan-identity:$userId:$planId"

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
        // All instances use the same SharedPreferences file; migration and delayed writes serialize.
        private val namespaceLock = Any()
    }
}
