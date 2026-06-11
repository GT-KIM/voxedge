package com.conversationalai.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RuntimeReadinessStrip(state.runtimeReadiness)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conversation", style = MaterialTheme.typography.titleLarge)
            Text("state: ${state.loopState.name.lowercase()}", style = MaterialTheme.typography.labelMedium)
        }

        TranscriptTimeline(state.transcript)

        OutlinedTextField(
            value = state.typedText,
            onValueChange = { onAction(ConversationAction.UpdateTypedText(it)) },
            label = { Text("message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.recording,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = state.isReady(RuntimeReadinessKind.LLM) &&
                    state.isReady(RuntimeReadinessKind.TTS) &&
                    !state.busy,
                onClick = { onAction(ConversationAction.SubmitTypedTurn) },
            ) {
                Text(if (state.busy) "Working..." else "Send")
            }
            MicControl(state, onAction)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = state.isReady(RuntimeReadinessKind.ASR) &&
                    state.isReady(RuntimeReadinessKind.LLM) &&
                    state.isReady(RuntimeReadinessKind.TTS) &&
                    (!state.busy || state.recording),
                onClick = {
                    onAction(
                        if (state.recording) ConversationAction.StopPushToTalk else ConversationAction.StartPushToTalk,
                    )
                },
            ) {
                Text(if (state.recording) "Stop & answer" else "Push to talk")
            }
            OutlinedButton(
                enabled = state.isReady(RuntimeReadinessKind.VAD) && !state.busy,
                onClick = { onAction(ConversationAction.ToggleBargeIn) },
            ) {
                Text("Barge-in: " + if (state.bargeIn) "ON" else "OFF")
            }
            OutlinedButton(
                enabled = state.isReady(RuntimeReadinessKind.ASR) && !state.busy,
                onClick = { onAction(ConversationAction.ChangeLanguage(nextLanguage(state.asrLanguage))) },
            ) {
                Text("ASR: ${state.asrLanguage.uppercase()}")
            }
        }

        if (state.busy || state.handsFree || state.recording) {
            TextButton(onClick = { onAction(ConversationAction.CancelCurrentTurn) }) {
                Text("Cancel")
            }
        }

        LatencySummaryView(state.latencySummary)

        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    onAction(
                        if (state.settingsOpen) ConversationAction.CloseSettings else ConversationAction.OpenSettings,
                    )
                },
            ) {
                Text(if (state.settingsOpen) "Close settings" else "Settings")
            }
            TextButton(
                onClick = {
                    onAction(
                        if (state.diagnosticsOpen) ConversationAction.CloseDiagnostics else ConversationAction.OpenDiagnostics,
                    )
                },
            ) {
                Text(if (state.diagnosticsOpen) "Close diagnostics" else "Diagnostics")
            }
        }

        if (state.settingsOpen) {
            SettingsSheet(settings = state.settings, bargeIn = state.bargeIn, onAction = onAction)
        }

        if (state.diagnosticsOpen) {
            DebugRuntimePanel(state, onAction)
        }
    }
}

@Composable
fun RuntimeReadinessStrip(readiness: List<RuntimeReadiness>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        readiness.forEach { item ->
            Surface(color = readinessColor(item.status), tonalElevation = 1.dp) {
                Text(
                    "${item.label}: ${item.status.name.lowercase()}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
fun TranscriptTimeline(items: List<TranscriptItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            Text("No turns yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            items.forEach { item ->
                Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.role.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                        Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        if (item.interrupted && item.spokenContent.isNotBlank()) {
                            Text("spoken: ${item.spokenContent}", style = MaterialTheme.typography.labelSmall)
                        } else if (item.isStreaming) {
                            Text("streaming", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MicControl(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    val enabled = state.isReady(RuntimeReadinessKind.ASR) &&
        state.isReady(RuntimeReadinessKind.LLM) &&
        state.isReady(RuntimeReadinessKind.TTS) &&
        state.isReady(RuntimeReadinessKind.VAD) &&
        !state.busy
    Button(
        enabled = enabled || state.handsFree,
        onClick = {
            onAction(if (state.handsFree) ConversationAction.StopHandsFree else ConversationAction.StartHandsFree)
        },
    ) {
        Text(if (state.handsFree) "Stop listening" else "Start listening")
    }
}

@Composable
fun LatencySummaryView(summary: LatencySummary) {
    if (summary.summaryText.isBlank()) return
    Text(summary.summaryText, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DebugRuntimePanel(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Runtime diagnostics", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state.isReady(RuntimeReadinessKind.TTS) && !state.busy,
                onClick = { onAction(ConversationAction.RunDebugSpeak) },
            ) {
                Text(if (state.busy) "..." else "Speak")
            }
            Button(
                enabled = state.isReady(RuntimeReadinessKind.LLM) && !state.busy,
                onClick = { onAction(ConversationAction.RunDebugAskLlm) },
            ) {
                Text("Ask LLM")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state.isReady(RuntimeReadinessKind.TTS) &&
                    state.isReady(RuntimeReadinessKind.LLM) &&
                    !state.busy,
                onClick = { onAction(ConversationAction.RunDebugConverse) },
            ) {
                Text("Converse")
            }
            Button(
                enabled = state.isReady(RuntimeReadinessKind.ASR) && !state.busy,
                onClick = { onAction(ConversationAction.RunDebugAsrTest) },
            ) {
                Text("ASR test (wav)")
            }
        }
        if (state.llmOutput.isNotBlank()) {
            Text(state.llmOutput, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun readinessColor(status: RuntimeReadinessStatus) = when (status) {
    RuntimeReadinessStatus.READY -> MaterialTheme.colorScheme.secondaryContainer
    RuntimeReadinessStatus.INITIALIZING -> MaterialTheme.colorScheme.surfaceVariant
    RuntimeReadinessStatus.MISSING_ASSET -> MaterialTheme.colorScheme.errorContainer
    RuntimeReadinessStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    RuntimeReadinessStatus.BLOCKED -> MaterialTheme.colorScheme.tertiaryContainer
}

private fun nextLanguage(current: String): String = if (current == "ko") "en" else "ko"
