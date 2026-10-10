package com.planlock.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.planlock.plugin.BoundPluginContribution
import com.planlock.plugin.ContributionTone
import com.planlock.plugin.PluginCapability
import com.planlock.plugin.PluginContribution
import com.planlock.plugin.visibleContributions

/** Only these three host-owned shapes render; no plugin Compose/View callback crosses the slot. */
@Composable
fun PluginContributionContent(
    items: List<BoundPluginContribution>,
    granted: Set<PluginCapability>,
    onAction: (BoundPluginContribution, PluginContribution.ActionRow) -> Unit,
    onRequest: (BoundPluginContribution, PluginContribution.CapabilityRequest) -> Unit,
    actionsEnabled: Boolean = true,
) {
    val visible = visibleContributions(items, granted)
    if (visible.isEmpty()) return
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEach { bound ->
                when (val item = bound.contribution) {
                    // Form sections belong to the dedicated plan editor renderer, never detail slots.
                    is PluginContribution.FormSection -> Unit
                    is PluginContribution.StatusLine -> {
                        val available = item.requiresCapability == null || item.requiresCapability in granted
                        val tone = if (available) item.tone else ContributionTone.WARN
                        Text(if (available) item.text else item.unavailableText.orEmpty(), color = when (tone) {
                            ContributionTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            ContributionTone.ACTIVE -> MaterialTheme.colorScheme.primary
                            ContributionTone.WARN -> MaterialTheme.colorScheme.error
                        })
                    }
                    is PluginContribution.ActionRow -> Button(onClick = { onAction(bound, item) }, enabled = actionsEnabled) { Text(item.label) }
                    is PluginContribution.CapabilityRequest -> if (item.capabilityId !in granted) {
                        Text(item.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onRequest(bound, item) }) { Text("要求授权") }
                    }
                }
            }
        }
    }
}
