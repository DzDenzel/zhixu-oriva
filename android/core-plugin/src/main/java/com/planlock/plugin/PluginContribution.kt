package com.planlock.plugin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object PluginSlots {
    const val PLAN_DETAIL_PROTECTION = "plan.detail.protection"
    const val PLAN_EDIT_CONFIGURATION = "plan.edit.configuration"
    val registered: Set<String> = setOf(PLAN_DETAIL_PROTECTION, PLAN_EDIT_CONFIGURATION)
}

@Serializable
enum class ContributionTone {
    @SerialName("neutral") NEUTRAL,
    @SerialName("active") ACTIVE,
    @SerialName("warn") WARN,
}

@Serializable
enum class ContributionActionInput { NONE, APPLICATION_SINGLE }

/** Closed host-owned shapes. Labels and availability copy belong to the declaring plugin. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class PluginContribution {
    abstract val id: String
    abstract val slot: String
    abstract val shapeVersion: Int

    @Serializable @SerialName("FormSection")
    data class FormSection(
        override val id: String,
        override val slot: String,
        val title: String,
        val fields: List<PluginFormField>,
        override val shapeVersion: Int = 1,
    ) : PluginContribution()

    @Serializable @SerialName("StatusLine")
    data class StatusLine(
        override val id: String,
        override val slot: String,
        val text: String,
        val tone: ContributionTone = ContributionTone.NEUTRAL,
        val requiresCapability: PluginCapability? = null,
        val unavailableText: String? = null,
        override val shapeVersion: Int = 1,
    ) : PluginContribution()

    @Serializable @SerialName("ActionRow")
    data class ActionRow(
        override val id: String,
        override val slot: String,
        val label: String,
        val actionId: String,
        val input: ContributionActionInput = ContributionActionInput.NONE,
        override val shapeVersion: Int = 1,
    ) : PluginContribution()

    @Serializable @SerialName("CapabilityRequest")
    data class CapabilityRequest(
        override val id: String,
        override val slot: String,
        val reason: String,
        val capabilityId: PluginCapability,
        val actionId: String,
        override val shapeVersion: Int = 1,
    ) : PluginContribution()
}

@Serializable
data class PluginFormChoice(val value: String, val label: String)

@Serializable
data class PluginFormField(
    val id: String,
    val kind: String,
    val label: String,
    val value: JsonPrimitive,
    val onEvent: String,
    val options: List<PluginFormChoice> = emptyList(),
)

/** The event must match the declared field type before any plugin code receives it. */
fun validFormValue(field: PluginFormField, value: String): Boolean = when (field.kind) {
    "switch" -> value == "true" || value == "false"
    "text" -> value.length <= 4096
    "choice" -> field.options.any { it.value == value }
    else -> false
}

/** Registered catalog; system requests are distinct from the four runtime action interfaces. */
object PluginCapabilityCatalog {
    val system: Set<PluginCapability> = setOf(PluginCapability.USAGE_STATS, PluginCapability.ACCESSIBILITY,
        PluginCapability.DND, PluginCapability.NOTIFICATIONS, PluginCapability.LOCATION)
    val registered: Set<PluginCapability> = PluginCapability.entries.toSet()
}

internal fun validateContributions(manifest: PluginManifest) {
    val ids = mutableSetOf<String>()
    val fieldIds = mutableSetOf<String>()
    manifest.contributions.forEach { item ->
        require(item.id.isNotBlank() && ids.add(item.id)) { "Blank or duplicate contribution id" }
        require(item.slot in PluginSlots.registered) { "Unsupported contribution slot ${item.slot}" }
        require(item.shapeVersion == 1) { "Unsupported contribution shape version ${item.shapeVersion}" }
        when (item) {
            is PluginContribution.FormSection -> {
                require(manifest.contractVersion >= 2 && item.slot == PluginSlots.PLAN_EDIT_CONFIGURATION)
                require(item.title.isNotBlank() && item.fields.isNotEmpty() && item.fields.size <= 32)
                require(item.fields.map { it.id }.distinct().size == item.fields.size) { "Duplicate form field" }
                item.fields.forEach { field ->
                    require(fieldIds.add(field.id)) { "Duplicate plugin form field ${field.id}" }
                    require(field.id.isNotBlank() && field.label.isNotBlank() && field.onEvent.isNotBlank())
                    require(when (field.kind) {
                        "switch" -> !field.value.isString && field.value.booleanOrNull != null
                        "text" -> field.value.isString && field.value.content.length <= 4096
                        "choice" -> field.value.isString && field.options.isNotEmpty() && field.options.size <= 64 &&
                            field.options.map { it.value }.distinct().size == field.options.size &&
                            field.options.all { it.value.isNotBlank() && it.label.isNotBlank() } && validFormValue(field, field.value.content)
                        else -> false
                    }) { "Invalid form field ${field.id}" }
                }
            }
            is PluginContribution.StatusLine -> {
                require(item.text.isNotBlank()) { "Status text is required" }
                item.requiresCapability?.let { capability ->
                    require(capability in PluginCapabilityCatalog.system && capability in manifest.requiredCapabilities) { "Undeclared status capability" }
                    require(!item.unavailableText.isNullOrBlank()) { "Unavailable status copy is required" }
                }
            }
            is PluginContribution.ActionRow -> require(item.label.isNotBlank() && item.actionId.isNotBlank()) { "Action label/id are required" }
            is PluginContribution.CapabilityRequest -> {
                require(item.reason.isNotBlank() && item.actionId.isNotBlank()) { "Capability reason/action are required" }
                val evaluation = CapabilityPolicy.evaluate(manifest.requiredCapabilities, PluginCapabilityCatalog.system)
                require(item.capabilityId in evaluation.granted) { "Undeclared or unsupported system capability ${item.capabilityId}" }
            }
        }
    }
}

data class BoundPluginContribution(val pluginId: String, val pluginName: String, val contribution: PluginContribution)

fun visibleContributions(items: List<BoundPluginContribution>, granted: Set<PluginCapability>): List<BoundPluginContribution> = items.filterNot {
    val item = it.contribution
    item is PluginContribution.CapabilityRequest && item.capabilityId in granted
}

/** No stored contribution projection: removing a loaded plugin immediately removes its rows. */
fun projectContributions(plugins: List<LoadedPlugin>, slot: String): List<BoundPluginContribution> =
    plugins.sortedBy { it.manifest.id }.flatMap { plugin ->
        plugin.manifest.contributions.filter { it.slot == slot }.map {
            BoundPluginContribution(plugin.manifest.id, plugin.manifest.name, it)
        }
    }

data class PluginActionEvent(
    val planId: Long,
    val actionId: String,
    val packageName: String? = null,
    val appName: String? = null,
    val activeUntilMillis: Long = 0,
    val fieldId: String? = null,
    val value: String? = null,
)
fun interface PluginActionHandler { fun onAction(event: PluginActionEvent): String? }
enum class PluginActionStatus { EXECUTED, UNAVAILABLE, FAILED_OPEN }
data class PluginActionResult(val status: PluginActionStatus, val message: String? = null)

fun dispatchContributionAction(plugins: List<LoadedPlugin>, pluginId: String, event: PluginActionEvent): PluginActionResult {
    val plugin = plugins.firstOrNull { it.manifest.id == pluginId } ?: return PluginActionResult(PluginActionStatus.UNAVAILABLE)
    val declared = plugin.manifest.contributions.filterIsInstance<PluginContribution.ActionRow>().firstOrNull { it.actionId == event.actionId }
    val handler = plugin.instance as? PluginActionHandler
    val field = plugin.manifest.contributions.filterIsInstance<PluginContribution.FormSection>()
        .flatMap { it.fields }.firstOrNull { it.onEvent == event.actionId && it.id == event.fieldId }
    if (handler == null || (declared == null && field == null)) return PluginActionResult(PluginActionStatus.UNAVAILABLE)
    if (event.fieldId != null && (field == null || event.value == null || !validFormValue(field, event.value)))
        return PluginActionResult(PluginActionStatus.UNAVAILABLE)
    if (declared?.input == ContributionActionInput.APPLICATION_SINGLE && (event.packageName.isNullOrBlank() || event.activeUntilMillis <= 0))
        return PluginActionResult(PluginActionStatus.UNAVAILABLE)
    return try { PluginActionResult(PluginActionStatus.EXECUTED, handler.onAction(event)) }
    catch (failure: Throwable) { PluginActionResult(PluginActionStatus.FAILED_OPEN, failure.message) }
}
