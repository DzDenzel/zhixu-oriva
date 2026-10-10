package com.planlock.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.planlock.plugin.*

/** Closed editor schema. Plugin code receives data events, never Compose callbacks. */
@Composable
fun PluginFormContent(section: PluginContribution.FormSection, values: Map<String, String>, busy: Boolean,
    onValue: (PluginFormField, String) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(section.title, style = MaterialTheme.typography.labelLarge)
            section.fields.forEach { field ->
                val saved = values[field.id] ?: field.value.content
                when (field.kind) {
                    "switch" -> SettingSwitch(field.label, "", saved == "true") { if (!busy) onValue(field, it.toString()) }
                    "choice" -> {
                        Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        field.options.forEach { choice ->
                            Row {
                                RadioButton(selected = saved == choice.value, enabled = !busy, onClick = { onValue(field, choice.value) })
                                TextButton(enabled = !busy, onClick = { onValue(field, choice.value) }) { Text(choice.label) }
                            }
                        }
                    }
                    "text" -> {
                        var draft by remember(saved) { mutableStateOf(saved) }
                        OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) draft = it }, label = { Text(field.label) },
                            enabled = !busy, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy && draft != saved, onClick = { onValue(field, draft) }) { Text("应用") }
                    }
                }
            }
        }
    }
}
