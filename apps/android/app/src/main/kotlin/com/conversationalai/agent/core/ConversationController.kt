package com.conversationalai.agent.core

import android.util.Log
import com.conversationalai.agent.asr.AsrEngine
import com.conversationalai.agent.audio.MicStream
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.audio.SpeechEnhancer
import com.conversationalai.agent.audio.StreamingPcmPlayer
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Speech-loop states (docs/design/speech_loop_state_machine.md). */
enum class ConvState { IDLE, LISTENING, CAPTURING, TRANSCRIBING, GENERATING, SPEAKING, RECOVERING }

/** Durable per-turn record (subset of the shared/mcp TurnRecord contract). */
data class TurnRecord(
    val generationId: Long,
    val userText: String,
    val replyText: String,
    val asrMs: Long,
    val ttftMs: Long,
    val firstPcmMs: Long,
    val totalMs: Long,
    val bargedIn: Boolean = false,
    val spokenContent: String = "",
    /** Tools the agentic loop ran this turn, e.g. "set_timer(ok)" — for the session timeline. */
    val toolsUsed: List<String> = emptyList(),
)

/**
 * Step-5 ConversationController: the speech-loop state machine + the single canonical
 * mic -> ASR -> LLM -> TTS pipeline. Hands-free ([start]/[stop]) uses an always-on [MicStream]
 * (VAD + AEC); one-shot typed/PTT input uses [runTurn].
 *
 * Barge-in (5c): the mic stays open during SPEAKING. On speech ONSET while GENERATING/SPEAKING we
 * (1) bump the generation_id cancel epoch, (2) interrupt playback within ~one buffer, (3) abort the
 * in-flight LLM decode. Region workers DROP work whose gid is stale. The barge-in utterance arrives
 * via onUtterance and becomes the next turn (the consumer serializes turns, so TTS isn't re-entered).
 */
class ConversationController(
    private val vadModelPath: String,
    private val enhancer: SpeechEnhancer?,
    private val asr: AsrEngine,
    private val llm: LlmEngine,
    private val tts: TtsEngine,
    private val inputBuilder: ClauseInputBuilder,
    private val scope: CoroutineScope,
    private val onState: (ConvState) -> Unit = {},
    private val onUserText: (String) -> Unit = {},
    private val onAssistantDelta: (String) -> Unit = {},
    private val onTurn: (TurnRecord) -> Unit = {},
    // Debug: raw captured utterance (post AEC/NS, PRE neural denoiser) for the host CER eval harness.
    private val onUtteranceCaptured: (FloatArray) -> Unit = {},
    private val playerFactory: () -> PcmStreamPlayer = { StreamingPcmPlayer() },
    private val eventLogger: RuntimeEventLogger? = null,
    // Optional on-device tools: enables the agentic tool-use loop (SpeechTurnRunner) when the
    // engine is session-capable. Null/empty = plain conversational turns, exactly as before.
    private val tools: com.conversationalai.agent.core.tools.ToolRegistry? = null,
    // Optional durable memory: its fact snapshot is injected into the system prompt each re-prefill
    // so the model is grounded on saved facts without having to call recall_facts itself.
    private val memory: com.conversationalai.agent.core.memory.MemoryStore? = null,
) {
    /** Barge-in is EXPERIMENTAL and OFF by default: AEC residual on this device still causes the
     *  assistant to interrupt itself. When off, the mic stays on but turn-time speech is ignored
     *  (5b behavior: the user waits for the assistant to finish). Toggle from the UI to experiment. */
    @Volatile var bargeInEnabled = false

    /** Tool use on/off (settings). Changing it rewrites the system prompt (tools module), so the
     *  persistent LLM session is reset and re-prefilled on the next turn. */
    @Volatile var toolsEnabled = true
        private set

    /** TTS flow-matching steps per clause (settings): K=6 default (listening-matched to K=8),
     *  K=4 trades some quality for ~70 ms less per-clause synthesis. */
    @Volatile var ttsFlowSteps = 6

    /** SPECULATIVE turns (settings): at the mic's early silence checkpoint (~250 ms) the turn
     *  starts immediately — ASR, LLM prefill/decode, even TTS synthesis — while audible playback
     *  and tool execution stay gated. When the real endpoint (~600 ms) confirms the same
     *  transcript, the gate opens (the head start is pure saved latency); if the user resumes
     *  speaking or the transcripts differ, the speculative work is cancelled via the epoch. */
    @Volatile var speculativeEnabled = true

    fun setToolsEnabled(enabled: Boolean) {
        if (toolsEnabled == enabled) return
        toolsEnabled = enabled
        llm.setToolRegistry(if (enabled) tools else null)   // native-function-calling engines
        llm.resetSession()
        sessionLang = null
    }

    /** Confirmation policy for side-effecting tools (settings). Lives on the registry so the
     *  prompt-convention loop and engine-native tool calls share the same gate. */
    fun setConfirmActions(enabled: Boolean) {
        tools?.confirmSideEffects = enabled
    }

    /** Start a fresh session (UI "New session"): cancels any in-flight turn and clears the
     *  transcript history, rolling summary, and the engine's KV session. Listening state (if
     *  hands-free is running) is untouched — the next utterance opens the new session. */
    fun resetConversation() {
        generationEpoch.cancel()
        activePlayer?.interrupt()
        llm.abort()
        history.clear()
        rollingSummary = null
        sessionLang = null
        llm.resetSession()
        eventLogger?.log("conversation.reset")
    }

    /** Switch to a persisted session: like [resetConversation] but seeds the transcript history
     *  with [turns] (most recent kept) and the session's rolling [summary], so the next turn
     *  re-prefills with the restored context — including what was already compacted away. */
    fun restoreHistory(turns: List<PromptAssembler.Turn>, summary: String? = null) {
        resetConversation()
        for (t in turns.takeLast(MAX_HISTORY_TURNS)) history.addLast(t)
        rollingSummary = summary?.takeIf { it.isNotBlank() }
        eventLogger?.log(
            "conversation.restored",
            attributes = mapOf("turns" to history.size, "has_summary" to (rollingSummary != null)),
        )
    }

    /** The live rolling summary (persisted with the session so switches keep long-term context). */
    fun currentSummary(): String? = rollingSummary

    private val history = ArrayDeque<PromptAssembler.Turn>()   // recent turns for multi-turn context
    // Output language pinned into the live LLM session's system prompt (null = no session yet).
    // A turn in a different language forces a session re-prefill with the matching system prompt.
    private var sessionLang: PromptAssembler.Lang? = null
    // Rolling conversation summary (config: llm.rolling_summary_tokens). Generated right before an
    // occupancy-driven eviction re-prefill, then carried in the system prompt so long sessions keep
    // continuity after old turns leave both the KV cache and the transcript window.
    private var rollingSummary: String? = null
    @Volatile private var running = false
    private val stateMachine = SpeechLoopStateMachine()
    @Volatile private var speakingSinceNs = 0L   // when SPEAKING began (for the barge-in grace window)
    @Volatile private var activePlayer: PcmStreamPlayer? = null
    private val generationEpoch = GenerationEpoch()
    private var micStream: MicStream? = null
    private var utterances: Channel<FloatArray>? = null
    private var consumerJob: Job? = null

    private class Speculation(
        val gid: Long,
        val text: String,
        val asrMs: Long,
        val startedAtNs: Long,
        val gate: kotlinx.coroutines.CompletableDeferred<Unit>,
    ) {
        @Volatile var job: Job? = null
        @Volatile var record: TurnRecord? = null
    }

    @Volatile private var speculation: Speculation? = null
    private val turnRunner = SpeechTurnRunner(
        llm = llm,
        tts = tts,
        inputBuilder = inputBuilder,
        playerFactory = playerFactory,
        generationEpoch = generationEpoch,
        onPlayerStarted = { activePlayer = it },
        onPlayerStopped = { player -> if (activePlayer === player) activePlayer = null },
        onState = { setState(it) },
        onSpeakingStarted = { speakingSinceNs = System.nanoTime() },
        eventLogger = eventLogger,
        tools = tools,
        flowSteps = { ttsFlowSteps },
    )

    init {
        // Engines with native function calling receive the registry up front (no-op otherwise).
        if (tools != null) llm.setToolRegistry(tools)
    }

    val isRunning: Boolean get() = running
    private val state: ConvState get() = stateMachine.current

    // --- hands-free always-listening loop ---

    fun start() {
        if (running) return
        running = true
        history.clear()   // fresh conversation
        rollingSummary = null
        llm.resetSession(); sessionLang = null   // KV session mirrors the transcript lifecycle
        eventLogger?.log("conversation.start", attributes = mapOf("barge_in_enabled" to bargeInEnabled))
        val ch = Channel<FloatArray>(Channel.UNLIMITED)
        utterances = ch
        consumerJob = scope.launch(Dispatchers.Default) {
            for (samples in ch) handleUtterance(samples)
        }
        val mic = MicStream(
            vadModelPath = vadModelPath,
            onSpeechStart = { onSpeechStart() },
            // Barge-in off: only accept utterances detected while LISTENING (ignore turn-time mic /
            // TTS leakage). Barge-in on: accept always (the barge-in utterance becomes the next turn).
            onUtterance = { s ->
                if (running && (bargeInEnabled || state == ConvState.LISTENING || speculation != null)) {
                    ch.trySend(s)
                }
            },
            // Early silence checkpoint -> start the turn speculatively (playback/tools gated).
            onUtteranceCandidate = { s -> candidateUtterance(s) },
            // Pick the capture profile: barge-in needs the AEC/VOICE_COMMUNICATION path; otherwise use
            // the ASR-tuned VOICE_RECOGNITION source (no AEC/NS) for a cleaner signal to the recognizer.
            bargeIn = bargeInEnabled,
        )
        micStream = mic
        if (!mic.start()) { Log.e(TAG, "mic start failed"); stop(); return }
        setState(ConvState.LISTENING)
    }

    fun stop() {
        eventLogger?.log("conversation.stop", attributes = mapOf("was_running" to running))
        running = false
        micStream?.stop(); micStream = null
        speculation?.gate?.complete(Unit); speculation = null
        generationEpoch.cancel()             // invalidate any in-flight turn
        activePlayer?.interrupt()
        llm.abort()
        utterances?.close(); utterances = null
        consumerJob?.cancel(); consumerJob = null
        setState(ConvState.IDLE)
    }

    /** Sustained speech onset (capture thread): if a SPECULATIVE turn is in flight the user kept
     *  talking — cancel it (nothing was audible yet) and keep capturing. Otherwise, if the
     *  assistant is audibly responding, barge in — but ignore the first [GRACE_MS] of SPEAKING
     *  (playback-onset / echo ramp) to avoid self-interrupt. */
    private fun onSpeechStart() {
        if (cancelSpeculation("speech_resumed")) {
            setState(ConvState.CAPTURING)
            return
        }
        if (!bargeInEnabled) return       // experimental; off by default (no self-interruption)
        val speaking = state == ConvState.SPEAKING
        val generating = state == ConvState.GENERATING
        if (!speaking && !generating) return
        if (speaking) {
            val sinceMs = (System.nanoTime() - speakingSinceNs) / 1_000_000
            if (sinceMs < GRACE_MS) { Log.i(TAG, "barge-in ignored (grace ${sinceMs}ms)"); return }
        }
        generationEpoch.cancel()          // new cancel epoch -> stale-drops the running turn
        activePlayer?.interrupt()          // stop audible playback now (<~one buffer)
        llm.abort()                        // unblock the in-flight decode
        setState(ConvState.CAPTURING)
        eventLogger?.log("control.barge_in", attributes = mapOf("state" to state.name))
        Log.i(TAG, "BARGE-IN")
    }

    internal suspend fun handleUtterance(samples: FloatArray) {
        if (!running) return
        onUtteranceCaptured(samples)   // debug dump of the real captured signal (pre-denoise)
        // A speculative turn may already be running for this utterance: confirm or discard it.
        val spec = speculation
        if (spec != null) {
            speculation = null
            if (generationEpoch.isCurrent(spec.gid)) {
                val finalHeard = transcribeSamples(samples, event = "asr.final")
                if (finalHeard != null && transcriptsEquivalent(finalHeard.first, spec.text)) {
                    commitSpeculation(spec)
                    return
                }
                // Transcript mismatch: kill the speculative turn, answer the REAL transcript.
                generationEpoch.cancel()
                llm.abort()
                spec.gate.complete(Unit)
                spec.job?.join()
                eventLogger?.log(
                    "speculation.cancel",
                    generationId = spec.gid,
                    attributes = mapOf("reason" to "transcript_mismatch"),
                )
                setState(ConvState.CAPTURING)
                if (finalHeard == null) {
                    if (running) setState(ConvState.LISTENING)
                    return
                }
                runConfirmedTurn(finalHeard.first, finalHeard.second)
                return
            }
            // Speculation was already cancelled (user resumed speaking): this utterance is the
            // longer, authoritative one — handle it normally below.
        }
        // Half-duplex (barge-in off): mute the mic for the whole turn so the assistant's own TTS
        // playback can't leak back in as the next utterance. (Barge-in on keeps the mic live.)
        if (!bargeInEnabled) micStream?.muted = true
        val text = transcribe(samples)
        if (text == null) {
            if (!bargeInEnabled) micStream?.muted = false
            if (running) setState(ConvState.LISTENING)
            return
        }
        runConfirmedTurn(text.first, text.second)
    }

    /** The post-confirmation tail of a hands-free turn (shared by all paths). */
    private suspend fun runConfirmedTurn(userText: String, asrMs: Long) {
        if (!bargeInEnabled) micStream?.muted = true
        onUserText(userText)
        val rec = generateAndSpeak(generationEpoch.next(), userText, asrMs) { onAssistantDelta(it) }
        onTurn(rec)
        if (!bargeInEnabled) {
            kotlinx.coroutines.delay(TAIL_GUARD_MS)   // let the speaker's acoustic tail / reverb clear
            micStream?.muted = false                  // (MicStream resets the VAD on un-mute)
        }
        if (running) setState(ConvState.LISTENING)
    }

    /** Open the gate of a confirmed speculative turn and finish it like a normal one. The time
     *  between the early checkpoint and now is the turn's head start — pure saved latency. */
    private suspend fun commitSpeculation(spec: Speculation) {
        if (!bargeInEnabled) micStream?.muted = true   // mute BEFORE audio can start
        eventLogger?.log(
            "speculation.commit",
            generationId = spec.gid,
            attributes = mapOf("head_start_ms" to (System.nanoTime() - spec.startedAtNs) / 1_000_000),
        )
        spec.gate.complete(Unit)
        spec.job?.join()
        spec.record?.let { onTurn(it) }
        if (!bargeInEnabled) {
            kotlinx.coroutines.delay(TAIL_GUARD_MS)
            micStream?.muted = false
        }
        if (running) setState(ConvState.LISTENING)
    }

    /** Mic early-checkpoint callback (capture thread): kick off a speculative turn. */
    internal fun candidateUtterance(samples: FloatArray) {
        if (!running || !speculativeEnabled) return
        if (state != ConvState.LISTENING || speculation != null) return
        scope.launch(Dispatchers.Default) { startSpeculation(samples) }
    }

    private suspend fun startSpeculation(samples: FloatArray) {
        if (!running || speculation != null || state != ConvState.LISTENING) return
        val heard = transcribeSamples(samples, event = "asr.candidate") ?: return
        if (speculation != null || state != ConvState.LISTENING) return
        val spec = Speculation(
            gid = generationEpoch.next(),
            text = heard.first,
            asrMs = heard.second,
            startedAtNs = System.nanoTime(),
            gate = kotlinx.coroutines.CompletableDeferred(),
        )
        speculation = spec
        eventLogger?.log(
            "speculation.start",
            generationId = spec.gid,
            attributes = mapOf("text_chars" to spec.text.length, "asr_ms" to spec.asrMs),
        )
        onUserText(spec.text)
        setState(ConvState.TRANSCRIBING)   // LISTENING -> TRANSCRIBING; the runner moves on
        spec.job = scope.launch(Dispatchers.Default) {
            spec.record = generateAndSpeak(spec.gid, spec.text, spec.asrMs, gate = { spec.gate.await() }) {
                onAssistantDelta(it)
            }
        }
    }

    /** Cancel an in-flight speculative turn (user resumed speaking / stop). False if none. */
    private fun cancelSpeculation(reason: String): Boolean {
        val spec = speculation ?: return false
        speculation = null
        if (!generationEpoch.isCurrent(spec.gid)) return false
        generationEpoch.cancel()
        activePlayer?.interrupt()   // nothing audible yet (gate), but stop the player cleanly
        llm.abort()
        spec.gate.complete(Unit)    // release the gated consumer; the stale epoch ends it
        eventLogger?.log(
            "speculation.cancel",
            generationId = spec.gid,
            attributes = mapOf("reason" to reason),
        )
        Log.i(TAG, "speculation cancelled ($reason)")
        return true
    }

    // --- one-shot turn (typed / push-to-talk) ---

    suspend fun runTurn(userText: String, asrMs: Long = 0L, onDelta: (String) -> Unit): TurnRecord {
        val rec = generateAndSpeak(generationEpoch.next(), userText, asrMs, onDelta = onDelta)
        if (!running) setState(ConvState.IDLE)
        return rec
    }

    // --- shared stages ---

    private fun transcribe(utterance: FloatArray): Pair<String, Long>? {
        setState(ConvState.TRANSCRIBING)
        return transcribeSamples(utterance, event = "asr.final")
    }

    /** ASR without state-machine side effects (shared by the normal and speculative paths). */
    private fun transcribeSamples(utterance: FloatArray, event: String): Pair<String, Long>? {
        val t0 = System.nanoTime()
        val (clean, sr) = enhancer?.enhance(utterance, 16000) ?: (utterance to 16000)
        val text = asr.transcribe(clean, sr)
        val asrMs = (System.nanoTime() - t0) / 1_000_000
        if (!hasSpeech(text)) {
            eventLogger?.log(
                event = "asr.no_speech",
                elapsedMs = asrMs,
                attributes = mapOf("asr_ms" to asrMs, "sample_rate" to sr, "samples" to clean.size),
            )
            Log.i(TAG, "skip non-speech: \"$text\"")
            return null
        }
        eventLogger?.log(
            event = event,
            elapsedMs = asrMs,
            attributes = mapOf(
                "asr_ms" to asrMs,
                "sample_rate" to sr,
                "samples" to clean.size,
                "text_chars" to text.length,
                "asr_engine" to asr.name(),
            ),
        )
        Log.i(TAG, "heard/$event (${asrMs}ms): $text")
        return text to asrMs
    }

    private suspend fun generateAndSpeak(
        gid: Long,
        userText: String,
        asrMs: Long,
        gate: (suspend () -> Unit)? = null,
        onDelta: (String) -> Unit,
    ): TurnRecord {
        eventLogger?.log(
            event = "turn.start",
            generationId = gid,
            attributes = mapOf(
                "source" to if (asrMs > 0L) "voice" else "typed",
                "user_text_chars" to userText.length,
                "asr_ms" to asrMs,
            ),
        )
        tools?.beginTurn(gid)   // advances the side-effect confirmation gate
        // Session-wise prompting: continue the warm KV session with just the new turn, or reset
        // and re-prefill the recent transcript (first turn, language switch, post-abort, or the
        // context window filling up — re-prefill is also how old turns get evicted from KV).
        val turnLang = PromptAssembler.resolveLang(userText)
        val occupancy = llm.contextOccupancyPercent()
        val warmTurn = LlmSessionPolicy.canContinue(
            sessionCapable = llm.sessionCapable,
            sessionWarm = llm.sessionWarm(),
            occupancyPercent = occupancy,
            sessionLang = sessionLang,
            turnLang = turnLang,
        )
        val template = ChatTemplate.fromId(llm.chatTemplateId())
        // Advertise tools in the system prompt only for the prompt-convention loop; engines with
        // native function calling declare them through the runtime instead.
        val toolSpecs = if (toolsEnabled && tools != null && !tools.isEmpty &&
            llm.sessionCapable && !llm.handlesToolsNatively
        ) {
            tools.specs
        } else {
            emptyList()
        }
        // Re-prefill strategy: rewind-capable engines keep their KV and prefix-match the full
        // transcript (only the divergence is re-prefilled — e.g. a dropped barged-in turn);
        // others reset and prefill from scratch. Rewind needs something IN the cache to match
        // (occupancy > 0); a cold start prefills plainly.
        val rewindTurn = !warmTurn && llm.sessionCapable && llm.supportsRewind && occupancy > 0
        // Occupancy-driven eviction: distill the old turns into a rolling summary on the still-warm
        // session BEFORE they are dropped, then keep only the freshest turns in the transcript.
        if (!warmTurn && llm.sessionCapable && llm.sessionWarm() &&
            occupancy >= LlmSessionPolicy.MAX_OCCUPANCY_PERCENT
        ) {
            summarizeSession(gid)?.let { summary ->
                rollingSummary = summary
                while (history.size > SUMMARY_KEEP_TURNS) history.removeFirst()
            }
        }
        val prompt = if (warmTurn) {
            template.incremental(userText)
        } else {
            if (llm.sessionCapable && !rewindTurn) llm.resetSession()
            sessionLang = turnLang
            val base = PromptAssembler.systemPrompt(
                lang = turnLang, userSample = userText, tools = toolSpecs,
                facts = memory?.promptSnapshot().orEmpty(),
            )
            val system = rollingSummary?.let { "$base Summary of the conversation so far: $it" } ?: base
            llm.setSystemPrompt(system)   // "raw" engines apply this on session (re)creation
            template.full(system, history.toList(), userText)
        }
        eventLogger?.log(
            event = "prompt.assembled",
            generationId = gid,
            attributes = mapOf(
                "prompt_chars" to prompt.length,
                "history_turns" to history.size,
                "session_mode" to if (warmTurn) "warm" else if (rewindTurn) "rewind" else "full",
                "context_occupancy_pct" to occupancy,
                "lang" to turnLang.name,
            ),
        )
        val rec = turnRunner.run(gid, prompt, userText, asrMs, onDelta, template, toolsEnabled, rewindTurn, gate)
        // Record into history for multi-turn context (skip interrupted turns; cap recent turns).
        val replyText = rec.replyText.trim()
        if (!rec.bargedIn && replyText.isNotEmpty()) {
            history.addLast(PromptAssembler.Turn(userText, replyText))
            while (history.size > MAX_HISTORY_TURNS) history.removeFirst()
        }
        Log.i(TAG, "turn gid=$gid asr=${asrMs}ms TTFT=${rec.ttftMs}ms firstPCM=${rec.firstPcmMs}ms total=${rec.totalMs}ms bargedIn=${rec.bargedIn} hist=${history.size}")
        return rec
    }

    /** One quiet generation on the still-warm session distilling the conversation so far.
     *  Never spoken; bounded by the engine's max-response-tokens cap. Null on failure. */
    private fun summarizeSession(gid: Long): String? {
        val template = ChatTemplate.fromId(llm.chatTemplateId())
        val sb = StringBuilder()
        val t0 = System.nanoTime()
        val result = llm.generate(template.incremental(SUMMARY_PROMPT)) { sb.append(it) }
        val summary = sb.toString().trim().takeIf { it.isNotEmpty() && result == com.conversationalai.agent.llm.LlmEngine.Result.OK }
        eventLogger?.log(
            event = "session.summary",
            generationId = gid,
            elapsedMs = (System.nanoTime() - t0) / 1_000_000,
            attributes = mapOf("ok" to (summary != null), "chars" to (summary?.length ?: 0)),
        )
        Log.i(TAG, "rolling summary (${summary?.length ?: 0} chars): ${summary?.take(80)}")
        return summary
    }

    // --- test hooks: JVM unit tests drive the mic-callback entry points directly ---
    internal fun testEnterListening() {
        running = true
        setState(ConvState.LISTENING)
    }
    internal fun testSpeechOnset() = onSpeechStart()

    private fun setState(s: ConvState) {
        stateMachine.transitionTo(s)
        onState(s)
        eventLogger?.log("state.changed", attributes = mapOf("state" to s.name))
        Log.i(TAG, "state=$s")
    }
    private fun hasSpeech(text: String) = text.any { it.isLetterOrDigit() }

    companion object {
        /** Speculative-commit gate: the early (~250 ms VAD) and final (~600 ms VAD) transcripts
         *  see slightly different audio tails, so an exact match needlessly cancels valid
         *  speculative turns over trivial ASR noise (case, spacing, a trailing period) and throws
         *  away the head start. Forgive those while still cancelling on a real word change. */
        internal fun transcriptsEquivalent(a: String, b: String): Boolean =
            normalizeTranscript(a) == normalizeTranscript(b)

        private fun normalizeTranscript(s: String): String =
            s.lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.', '!', '?', ',', ';', ':', '。', '！', '？', ' ')

        private const val TAG = "ConvController"
        private const val GRACE_MS = 300L      // ignore barge-in this long after SPEAKING starts
        private const val TAIL_GUARD_MS = 350L // half-duplex: keep mic muted this long after playback
        private const val MAX_HISTORY_TURNS = 6   // recent turns kept for context (within ctx 4096)
        private const val SUMMARY_KEEP_TURNS = 2  // turns kept verbatim once a rolling summary exists
        private const val SUMMARY_PROMPT =
            "Summarize our conversation so far in two or three short sentences, in the language " +
                "we have been speaking. Mention names, decisions, and open questions. Plain text only."
    }
}
