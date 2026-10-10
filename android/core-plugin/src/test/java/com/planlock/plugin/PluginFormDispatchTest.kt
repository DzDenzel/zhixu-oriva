package com.planlock.plugin

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PluginFormDispatchTest {
    private class Handler : PlanLockPlugin, PluginActionHandler {
        var calls = 0
        override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = Unit
        override fun onAction(event: PluginActionEvent): String { calls++; return event.value.orEmpty() }
    }
    private val fields = listOf(
        PluginFormField("enabled", "switch", "Enabled", JsonPrimitive(true), "changed"),
        PluginFormField("style", "choice", "Style", JsonPrimitive("brief"), "changed",
            listOf(PluginFormChoice("brief", "Brief"), PluginFormChoice("gentle", "Gentle"))),
        PluginFormField("note", "text", "Note", JsonPrimitive(""), "changed"),
    )
    private fun loaded(handler: Handler) = listOf(LoadedPlugin(
        PluginManifest("demo", "Demo", "1", 2, "main", "official", PluginSourceType.OFFICIAL,
            contributions = listOf(PluginContribution.FormSection("config", PluginSlots.PLAN_EDIT_CONFIGURATION, "Advice", fields)), type = PluginType.JS), handler))

    @Test fun onlyDeclaredTypedFormEventsReachHandler() {
        val handler = Handler()
        val plugins = loaded(handler)
        for ((field, value) in listOf("enabled" to "yes", "style" to "unknown", "note" to "x".repeat(4097), "unknown" to "true")) {
            assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(plugins, "demo", PluginActionEvent(-42, "changed", fieldId = field, value = value)).status)
        }
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(plugins, "demo", PluginActionEvent(-42, "other", fieldId = "enabled", value = "true")).status)
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(plugins, "demo", PluginActionEvent(-42, "changed", fieldId = "enabled")).status)
        assertEquals(0, handler.calls)
        for ((field, value) in listOf("enabled" to "false", "style" to "gentle", "note" to "中文提示")) {
            val result = dispatchContributionAction(plugins, "demo", PluginActionEvent(-42, "changed", fieldId = field, value = value))
            assertEquals(PluginActionStatus.EXECUTED, result.status)
            assertEquals(value, result.message)
        }
        assertEquals(3, handler.calls)
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(emptyList(), "demo", PluginActionEvent(-42, "changed", fieldId = "enabled", value = "true")).status)
    }
}
