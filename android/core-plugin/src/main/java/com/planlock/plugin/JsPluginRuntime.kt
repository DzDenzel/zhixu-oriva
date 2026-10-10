package com.planlock.plugin

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Stateless invocations share host projections and declarations, never native references. */
class JsPluginRuntime(
    private val manifest: PluginManifest,
    private val source: String,
    private val queries: PluginQueryProvider,
    private val limits: JsRuntimeLimits = JsRuntimeLimits(),
) : PlanLockPlugin, PluginActionHandler, Closeable {
    private val closed = AtomicBoolean(false)

    init { require(manifest.type == PluginType.JS) { "JS runtime requires JS manifest" } }

    fun initialize(capabilities: PluginCapabilities = EmptyPluginCapabilities): String = execute(
        buildJsonObject { put("kind", "load") }, capabilities,
    )

    override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) {
        execute(buildJsonObject {
            put("kind", "decision"); put("planId", decision.planId)
            put("packageName", decision.packageName); put("appName", decision.appName?.let(::JsonPrimitive) ?: JsonNull)
            put("shouldTrack", decision.shouldTrack)
            put("configuration", configuration(decision.planId))
        }, capabilities)
    }

    override fun onAction(event: PluginActionEvent): String? {
        val result = Json.parseToJsonElement(execute(buildJsonObject {
            put("kind", "action"); put("planId", event.planId); put("actionId", event.actionId)
            put("fieldId", event.fieldId?.let(::JsonPrimitive) ?: JsonNull)
            put("value", event.value?.let(::JsonPrimitive) ?: JsonNull)
            put("packageName", event.packageName?.let(::JsonPrimitive) ?: JsonNull)
            put("appName", event.appName?.let(::JsonPrimitive) ?: JsonNull)
            put("activeUntilMillis", event.activeUntilMillis)
            put("configuration", configuration(event.planId, event))
        }, EmptyPluginCapabilities))
        val message = when (result) {
            JsonNull -> null
            is JsonPrimitive -> result.contentOrNull
            is JsonObject -> (result["message"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        return message?.take(500)
    }

    private fun configuration(planId: Long, proposal: PluginActionEvent? = null): JsonObject {
        val values = PluginHostConfiguration.read(manifest, planId).toMutableMap()
        if (proposal?.fieldId != null && proposal.value != null) values[proposal.fieldId] = proposal.value
        return JsonObject(values.mapValues { JsonPrimitive(it.value) })
    }

    // Each invocation owns a native context and bridge; independent event workers need no monitor.
    private fun execute(event: JsonObject, capabilities: PluginCapabilities): String {
        check(!closed.get()) { "JS plugin runtime is closed" }
        return QuickJsEngine.execute(source, manifest.entryPoint, event.toString(),
            JsPluginBridge(manifest.requiredCapabilities, capabilities, queries), limits)
    }

    // No JNI state survives an invocation. Closing prevents stale UI/event callbacks after uninstall.
    override fun close() { closed.set(true) }
}
