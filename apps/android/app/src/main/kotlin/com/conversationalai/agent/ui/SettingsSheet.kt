package com.conversationalai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Configuration sheet: LLM model selection (provisioned/active/selected made explicit — the
 * answer to "which model is actually running?"), live sampling tuning, and conversation toggles.
 * Sliders edit locally and commit one SetSampling action on release, so the engine isn't
 * re-configured on every drag frame.
 */
@Composable
fun SettingsSheet(
    settings: SettingsUiState,
    bargeIn: Boolean,
    onAction: (ConversationAction) -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            // --- LLM model ---
            Text("LLM model", style = MaterialTheme.typography.titleSmall)
            Text(
                "Active now: ${settings.activeModelName}",
                style = MaterialTheme.typography.bodySmall,
            )
            settings.models.forEach { model ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = model.selected,
                        enabled = model.provisioned,
                        onClick = { onAction(ConversationAction.SelectLlmModel(model.id)) },
                    )
                    Column {
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                        val badge = when {
                            model.active -> "active"
                            !model.provisioned -> "not provisioned (see MODELS.md)"
                            model.selected -> "loads on next launch"
                            else -> ""
                        }
                        if (badge.isNotEmpty()) {
                            Text(badge, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (settings.restartRequired) {
                Text(
                    "Restart the app to load the selected model.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- Sampling (applies live) ---
            Text("Sampling", style = MaterialTheme.typography.titleSmall)
            var temp by remember(settings.temp) { mutableFloatStateOf(settings.temp) }
            var topK by remember(settings.topK) { mutableIntStateOf(settings.topK) }
            var topP by remember(settings.topP) { mutableFloatStateOf(settings.topP) }
            val commit = { onAction(ConversationAction.SetSampling(temp, topK, topP)) }

            Text("temperature: ${"%.2f".format(temp)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = temp,
                onValueChange = { temp = it },
                onValueChangeFinished = commit,
                valueRange = 0.1f..1.2f,
            )
            Text("top-k: $topK", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = topK.toFloat(),
                onValueChange = { topK = it.toInt().coerceAtLeast(1) },
                onValueChangeFinished = commit,
                valueRange = 1f..100f,
            )
            Text("top-p: ${"%.2f".format(topP)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = topP,
                onValueChange = { topP = it },
                onValueChangeFinished = commit,
                valueRange = 0.5f..1.0f,
            )
            if (settings.samplingOverridden) {
                TextButton(onClick = { onAction(ConversationAction.ResetSampling) }) {
                    Text("Reset to model defaults")
                }
            }

            // --- Conversation ---
            Text("Conversation", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.toolsEnabled,
                    onCheckedChange = { onAction(ConversationAction.ToggleTools) },
                )
                Text(
                    "Device tools (timer, alarm, clock, battery, flashlight)",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = bargeIn,
                    onCheckedChange = { onAction(ConversationAction.ToggleBargeIn) },
                )
                Text(
                    "Barge-in (experimental)",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TextButton(onClick = { onAction(ConversationAction.CloseSettings) }) {
                Text("Close settings")
            }
        }
    }
}
