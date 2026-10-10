package com.planlock.plugin

enum class PluginInstallState { UNINSTALLED, INSTALLED, ENABLED }

object PluginLifecycle {
    fun state(installed: Boolean, enabled: Boolean): PluginInstallState = when {
        !installed -> PluginInstallState.UNINSTALLED
        enabled -> PluginInstallState.ENABLED
        else -> PluginInstallState.INSTALLED
    }

    fun isEffective(
        globalEnabled: Boolean,
        installed: Boolean,
        enabled: Boolean,
        sourceType: PluginSourceType,
        safeMode: Boolean,
    ): Boolean = globalEnabled && installed && enabled &&
        !(safeMode && sourceType == PluginSourceType.THIRD_PARTY)
}

data class CapabilityEvaluation(
    val granted: Set<PluginCapability>,
    val missing: Set<PluginCapability>,
)

object CapabilityPolicy {
    fun evaluate(
        requested: Set<PluginCapability>,
        registered: Set<PluginCapability>,
    ) = CapabilityEvaluation(
        granted = requested intersect registered,
        missing = requested - registered,
    )
}

class DeclaredPluginCapabilities(
    private val declared: Set<PluginCapability>,
    private val delegate: PluginCapabilities,
) : PluginCapabilities {
    override fun overlay(): OverlayCapability? = delegate.overlay().takeIf { PluginCapability.OVERLAY in declared }
    override fun home(): HomeCapability? = delegate.home().takeIf { PluginCapability.HOME in declared }
    override fun haptic(): HapticCapability? = delegate.haptic().takeIf { PluginCapability.HAPTIC in declared }
    override fun feedback(): FeedbackCapability? = delegate.feedback().takeIf { PluginCapability.FEEDBACK in declared }
}

data class LoadedPlugin(
    val manifest: PluginManifest,
    val instance: PlanLockPlugin,
)

enum class PluginDispatchStatus { EXECUTED, QUEUED, FAILED_OPEN }

data class PluginDispatchResult(
    val pluginId: String,
    val status: PluginDispatchStatus,
    val message: String? = null,
)

/** One plugin failure is contained and never prevents the host or other plugins from continuing. */
class PluginDispatcher {
    fun dispatch(
        plugins: List<LoadedPlugin>,
        decision: PluginDecision,
        capabilities: PluginCapabilities,
    ): List<PluginDispatchResult> = plugins.map { plugin ->
        try {
            plugin.instance.onDecision(
                decision,
                DeclaredPluginCapabilities(plugin.manifest.requiredCapabilities, capabilities),
            )
            PluginDispatchResult(plugin.manifest.id, PluginDispatchStatus.EXECUTED)
        } catch (failure: Throwable) {
            PluginDispatchResult(
                pluginId = plugin.manifest.id,
                status = PluginDispatchStatus.FAILED_OPEN,
                message = failure.message ?: failure::class.java.simpleName,
            )
        }
    }
}
