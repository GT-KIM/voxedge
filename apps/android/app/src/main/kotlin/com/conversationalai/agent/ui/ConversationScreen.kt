package com.conversationalai.agent.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * SESSION-WISE conversation screen in the coding-agent (Claude Code / Codex) style: a full-width
 * document-flow transcript — user turns as accent-prefixed lines, assistant prose as plain
 * paragraphs, tool executions as muted monospace action rows, a muted per-turn latency meta line,
 * and a "thinking..." status row while the agent works. Session header (state, active model,
 * context fill, New session) on top; the prompt box pinned at the bottom.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    // Left session drawer (like the coding-agent sidebars); two-way sync with sessionsOpen.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(state.sessionsOpen) {
        if (state.sessionsOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && state.sessionsOpen) {
            onAction(ConversationAction.CloseSessions)
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 300.dp)) {
                SessionDrawer(state, onAction)
            }
        },
    ) {
        ConversationContent(state, onAction)
    }
}

/** The session list panel (left drawer): new session + saved sessions, newest first. */
@Composable
fun SessionDrawer(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Sessions", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onAction(ConversationAction.NewSession) }) {
            Text("+ New session")
        }
        state.sessions.forEach { session ->
            val current = session.id == state.currentSessionId
            Surface(
                color = if (current) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
                            .clickable { onAction(ConversationAction.SelectSession(session.id)) },
                    ) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            session.updatedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onAction(ConversationAction.DeleteSession(session.id)) }) {
                        Text("\u2715", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.sessions.isEmpty()) {
            Text(
                "No saved sessions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationContent(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionHeader(state, onAction)
        RuntimeReadinessStrip(state.runtimeReadiness)

        // --- the session feed (document flow, Codex-style) ---
        val listState = rememberLazyListState()
        val items = state.transcript
        val working = state.loopState == SpeechLoopUiState.GENERATING ||
            state.loopState == SpeechLoopUiState.TRANSCRIBING ||
            state.loopState == SpeechLoopUiState.SPEAKING
        LaunchedEffect(items.size, items.lastOrNull()?.text?.length, working) {
            if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty() && !working) {
                item {
                    Text(
                        "No turns yet - speak or type to start the session.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(items, key = { it.id }) { item -> TurnBlock(item) }
            if (working && state.transcript.lastOrNull()?.isStreaming != true) {
                item { MetaRow(workingLabel(state.loopState)) }
            }
        }

        // --- status + pinned input ---
        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            state.statusMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

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

/** Session header: drawer toggle, loop state, active model + context fill, New session. */
@Composable
fun SessionHeader(
    state: ConversationUiState,
    onAction: (ConversationAction) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onAction(ConversationAction.OpenSessions) }) {
            Text("\u2630", style = MaterialTheme.typography.titleMedium)
        }
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

/**
 * One turn in the coding-agent document flow:
 *  - USER: accent "❯" prefix + medium-weight text (the prompt line).
 *  - ASSISTANT: muted monospace action rows for each tool run, plain prose paragraph, then a
 *    muted meta line (latency) — like a Codex/Claude Code action transcript.
 */
@Composable
fun TurnBlock(item: TranscriptItem) {
    if (item.role == TranscriptRole.USER) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "\u276F ",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.tools.forEach { tool -> ActionRow(tool) }
        if (item.text.isNotBlank()) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (item.interrupted) {
            val spoken = item.spokenContent.takeIf { it.isNotBlank() }
            Text(
                if (spoken != null) "\u2298 interrupted - spoken: \"$spoken\"" else "\u2298 interrupted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (item.isStreaming) {
            MetaRow("generating...")
        }
        if (item.meta.isNotBlank() && !item.isStreaming) {
            MetaRow(item.meta)
        }
    }
}

/** Muted monospace action row, e.g. "✓ ran set_timer" — the Codex command-line look. */
@Composable
fun ActionRow(tool: String) {
    val ok = tool.endsWith("(ok)")
    val name = tool.removeSuffix("(ok)").removeSuffix("(failed)")
    Text(
        (if (ok) "\u2713" else "\u2717") + " ran " + name + if (ok) "" else " (failed)",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
}

/** Muted single-line meta/status row ("thinking...", latency breakdowns). */
@Composable
fun MetaRow(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun workingLabel(loopState: SpeechLoopUiState): String = when (loopState) {
    SpeechLoopUiState.TRANSCRIBING -> "transcribing..."
    SpeechLoopUiState.SPEAKING -> "speaking..."
    else -> "thinking..."
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
