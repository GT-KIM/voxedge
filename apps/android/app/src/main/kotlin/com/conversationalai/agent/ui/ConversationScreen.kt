package com.conversationalai.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * SESSION-WISE conversation screen (the agent UI): a persistent multi-turn transcript timeline
 * fills the screen (auto-scrolling, user right / assistant left, tool chips and interruption
 * markers per turn), with a session header (state, active model, context fill, New session) and
 * the input controls pinned at the bottom. Settings/diagnostics open as bounded sheets.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionHeader(state, onAction)
        RuntimeReadinessStrip(state.runtimeReadiness)

        // --- the session timeline ---
        val listState = rememberLazyListState()
        val items = state.transcript
        LaunchedEffect(items.size, items.lastOrNull()?.text?.length) {
            if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        "No turns yet - speak or type to start the session.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(items, key = { it.id }) { item -> TranscriptBubble(item) }
        }

        // --- status + pinned input ---
        LatencySummaryView(state.latencySummary)
        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)

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
            if (state.busy || state.handsFree || state.recording) {
                TextButton(onClick = { onAction(ConversationAction.CancelCurrentTurn) }) {
                    Text("Cancel")
                }
            }
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
                enabled = state.isReady(RuntimeReadinessKind.ASR) && !state.busy,
                onClick = { onAction(ConversationAction.ChangeLanguage(nextLanguage(state.asrLanguage))) },
            ) {
                Text("ASR: ${state.asrLanguage.uppercase()}")
            }
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
                Text(if (state.diagnosticsOpen) "Close diag" else "Diag")
            }
        }

        if (state.settingsOpen) {
            Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                SettingsSheet(settings = state.settings, bargeIn = state.bargeIn, onAction = onAction)
            }
        }

        if (state.diagnosticsOpen) {
            Box(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                DebugRuntimePanel(state, onAction)
            }
        }
    }
}

/** Session header: loop state, active model + context fill, and the New session control. */
@Composable
fun SessionHeader(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Session", style = MaterialTheme.typography.titleLarge)
            val ctx = state.contextOccupancyPercent?.let { " - ctx $it%" } ?: ""
            val model = state.activeModelName.ifEmpty { "model unknown" }
            Text(
                "${state.loopState.name.lowercase()} - $model$ctx",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TextButton(
            enabled = !state.busy,
            onClick = { onAction(ConversationAction.NewSession) },
        ) {
            Text("New session")
        }
    }
}

/** One turn in the session timeline: user right-aligned, assistant left, tool chips inline. */
@Composable
fun TranscriptBubble(item: TranscriptItem) {
    val isUser = item.role == TranscriptRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            tonalElevation = if (isUser) 3.dp else 1.dp,
            color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.tools.isNotEmpty()) {
                    Text(
                        "tools: " + item.tools.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.text.isNotBlank()) {
                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.interrupted) {
                    val spoken = item.spokenContent.takeIf { it.isNotBlank() }
                    Text(
                        if (spoken != null) "interrupted - spoken: $spoken" else "interrupted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (item.isStreaming) {
                    Text("...", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Compact single-row readiness chips (horizontally scrollable). */
@Composable
fun RuntimeReadinessStrip(readiness: List<RuntimeReadiness>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        readiness.forEach { item ->
            Surface(color = readinessColor(item.status), tonalElevation = 1.dp) {
                Text(
                    "${item.label}: ${item.status.name.lowercase()}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
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
