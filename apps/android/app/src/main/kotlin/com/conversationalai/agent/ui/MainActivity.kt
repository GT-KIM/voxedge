package com.conversationalai.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.conversationalai.agent.asr.OfflineAsr
import com.conversationalai.agent.audio.AudioCapture
import com.conversationalai.agent.audio.PcmPlayer
import com.conversationalai.agent.audio.SpeechEnhancer
import com.conversationalai.agent.core.ConvState
import com.conversationalai.agent.core.ConversationController
import com.conversationalai.agent.core.PromptAssembler
import com.conversationalai.agent.core.RuntimeEventLogger
import com.conversationalai.agent.llm.LlmCatalog
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.llm.LlmModelSpec
import com.conversationalai.agent.tts.SupertonicTts
import com.conversationalai.agent.tts.TtsInputBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val EXTRA_DEBUG_TYPED_TURN = "debug_typed_turn"

/**
 * Phase 3 build-step-2 slice: type text -> synthesize one clause on the HTP -> play via AudioTrack.
 * No ASR/LLM yet. DLCs are pushed to filesDir/tts_dlc via adb (see step instructions).
 */
class MainActivity : ComponentActivity() {

    private lateinit var tts: SupertonicTts
    private lateinit var inputBuilder: TtsInputBuilder
    private lateinit var llm: LlmEngine
    private lateinit var llmModel: LlmModelSpec
    private lateinit var settingsController: SettingsController
    private var initialBargeIn = false
    private lateinit var asr: OfflineAsr
    private lateinit var enhancer: SpeechEnhancer
    private lateinit var controller: ConversationController
    private lateinit var eventLogger: RuntimeEventLogger
    private val capture = AudioCapture()
    private var initOk = false
    private var llmOk = false
    private var asrOk = false
    private var enhanceOk = false
    private var vadOk = false

    // SESSION state (the UI is session-wise): the accumulated turn timeline plus the
    // currently-streaming assistant reply. Hands-free turns arrive via controller callbacks;
    // typed/PTT/debug turns flow through runConversation — both append to the same timeline.
    private var convState by mutableStateOf(ConvState.IDLE)
    private var sessionItems by mutableStateOf(listOf<TranscriptItem>())
    private var streamingReply by mutableStateOf("")
    private var latencyLine by mutableStateOf("")
    private var ctxOccupancy by mutableStateOf<Int?>(null)
    private var itemSeq = 0
    private var capIdx = 0   // rotating index for dumped capture wavs (cap_dump/)
    private var status by mutableStateOf("starting...")
    private var micGranted by mutableStateOf(false)

    // Session persistence (left drawer): one JSONL file per session, auto-saved after each turn.
    private lateinit var sessionStore: SessionStore
    private var currentSessionId by mutableStateOf("")
    private var sessionList by mutableStateOf(listOf<SessionSummary>())

    private fun refreshSessionList() {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        sessionList = sessionStore.list().map {
            SessionSummary(it.id, it.title, fmt.format(java.util.Date(it.updatedMs)))
        }
    }

    private fun persistCurrentSession() {
        if (sessionItems.isEmpty()) return
        val title = sessionItems.firstOrNull { it.role == TranscriptRole.USER }?.text?.take(48)
            ?: "(untitled)"
        sessionStore.save(
            currentSessionId, title, System.currentTimeMillis(), sessionItems,
            summary = controller.currentSummary().orEmpty(),
        )
        refreshSessionList()
    }

    private fun appendUserItem(text: String) {
        streamingReply = ""
        sessionItems = sessionItems + TranscriptItem(
            id = "u${itemSeq++}", role = TranscriptRole.USER, text = text,
        )
    }

    private fun appendTurnResult(r: com.conversationalai.agent.core.TurnRecord) {
        streamingReply = ""
        val reply = r.replyText.trim()
        if (reply.isNotEmpty() || r.toolsUsed.isNotEmpty()) {
            sessionItems = sessionItems + TranscriptItem(
                id = "a${itemSeq++}",
                role = TranscriptRole.ASSISTANT,
                text = reply,
                interrupted = r.bargedIn,
                spokenContent = r.spokenContent,
                tools = r.toolsUsed,
                meta = "asr ${r.asrMs}ms / TTFT ${r.ttftMs}ms / firstPCM ${r.firstPcmMs}ms / total ${r.totalMs}ms",
            )
        }
        latencyLine = "asr ${r.asrMs}ms / TTFT ${r.ttftMs}ms / firstPCM ${r.firstPcmMs}ms / total ${r.totalMs}ms"
        ctxOccupancy = llm.contextOccupancyPercent().takeIf { it >= 0 }
        persistCurrentSession()
    }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
        if (!it) Log.w(TAG, "RECORD_AUDIO denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val runtime = RuntimeInitializer(this).initialize()
        tts = runtime.tts
        inputBuilder = runtime.inputBuilder
        llm = runtime.llm
        llmModel = runtime.llmModel
        asr = runtime.asr
        enhancer = runtime.enhancer
        initOk = runtime.initOk
        llmOk = runtime.llmOk
        asrOk = runtime.asrOk
        enhanceOk = runtime.enhanceOk
        vadOk = runtime.vadOk
        status = runtime.status
        eventLogger = RuntimeEventLogger(File(filesDir, "runtime_logs/turn_events.jsonl"))

        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        eventLogger.log(
            event = "session.start",
            attributes = mapOf(
                "tts_ready" to initOk,
                "llm_ready" to llmOk,
                "asr_ready" to asrOk,
                "enhancer_ready" to enhanceOk,
                "vad_ready" to vadOk,
                "mic_granted" to micGranted,
                "log_path" to File(filesDir, "runtime_logs/turn_events.jsonl").absolutePath,
            ),
        )

        // Offline tool registry + the durable memory store it shares (for prompt grounding).
        val deviceTools = com.conversationalai.agent.devicetools.DeviceTools.build(this)

        // Step-5 state machine: single canonical mic->ASR->LLM->TTS pipeline (hands-free + one-shot).
        controller = ConversationController(
            vadModelPath = File(filesDir, "vad/silero_vad.onnx").absolutePath,
            // GTCRN denoiser DISABLED in the main loop: host CER bench showed it did not improve ASR
            // (raw == gtcrn) and the Dolphin win (CER 0.30) was on the RAW pre-denoise signal. Feed
            // ASR the raw capture. (enhancer kept available for the debug Talk button / experiments.)
            enhancer = null,
            asr = asr, llm = llm, tts = tts, inputBuilder = inputBuilder,
            scope = lifecycleScope,
            eventLogger = eventLogger,
            onState = { convState = it },
            onUserText = { appendUserItem(it) },
            onAssistantDelta = { streamingReply += it },
            onTurn = { r -> appendTurnResult(r) },
            onUtteranceCaptured = { samples ->
                runCatching {
                    val dir = File(filesDir, "cap_dump").apply { mkdirs() }
                    val f = File(dir, "utt_${capIdx++ % 6}.wav")
                    com.conversationalai.agent.audio.WavWriter.write(f, samples, 16000)
                    Log.i(TAG, "dumped capture -> ${f.absolutePath} (${samples.size} samples)")
                }.onFailure { Log.e(TAG, "capture dump failed", it) }
            },
            // Offline device tools (clock/timer/alarm/battery/flashlight/calculate/memory): enables
            // the agentic tool-use loop on session-capable engines. The memory handle is also passed
            // so saved facts are injected into the system prompt (grounding without a recall call).
            tools = deviceTools.registry,
            memory = deviceTools.memory,
        )

        // Persisted configuration (model choice, sampling, toggles) — applied to the live
        // controller/engine now; the model choice itself was already honored by RuntimeInitializer.
        val store = PrefsSettingsStore(this)
        settingsController = SettingsController(
            store = store,
            activeModel = llmModel,
            llm = llm,
            controller = controller,
            isProvisioned = { spec -> LlmCatalog.isProvisioned(filesDir, spec) },
            inputBuilder = inputBuilder,
        )
        settingsController.applyStartupSettings()
        initialBargeIn = store.load().bargeIn

        sessionStore = SessionStore(File(filesDir, "sessions"))
        currentSessionId = "s${System.currentTimeMillis()}"
        refreshSessionList()

        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
        handleDebugTurnIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugTurnIntent(intent)
    }

    /** Headless adb test drive (dev-only): run one full typed turn (prompt -> LLM -> clause ->
     *  TTS -> playback) without touching the UI, so the pipeline can be exercised with the screen
     *  locked: `adb shell am start -n .../.ui.MainActivity --es debug_typed_turn "hello"`.
     *  Results land in logcat + the runtime JSONL event log. */
    private fun handleDebugTurnIntent(intent: android.content.Intent?) {
        val text = intent?.getStringExtra(EXTRA_DEBUG_TYPED_TURN)?.takeIf { it.isNotBlank() } ?: return
        Log.i(TAG, "debug_typed_turn: \"$text\"")
        lifecycleScope.launch {
            val summary = runConversation(text) {}
            val reply = sessionItems.lastOrNull { it.role == TranscriptRole.ASSISTANT }?.text ?: ""
            Log.i(TAG, "debug_typed_turn reply: \"$reply\"")
            Log.i(TAG, "debug_typed_turn done: $summary")
        }
    }

    /** One assistant turn via the controller (typed/PTT/debug entry). Appends the user turn and
     *  the finished assistant turn to the SESSION timeline; deltas stream into [streamingReply]. */
    private suspend fun runConversation(userText: String, asrMs: Long = 0L, onToken: (String) -> Unit): String {
        appendUserItem(userText)
        val r = controller.runTurn(userText, asrMs = asrMs) { streamingReply += it; onToken(it) }
        appendTurnResult(r)
        return "TTFT=${r.ttftMs}ms / firstPCM=${r.firstPcmMs}ms / total=${r.totalMs}ms"
    }

    /** Session-wise "New session": clears the timeline and the controller's history/summary/KV. */
    private fun handleNewSession(setMsg: (String) -> Unit) {
        persistCurrentSession()
        controller.resetConversation()
        sessionItems = emptyList()
        streamingReply = ""
        latencyLine = ""
        ctxOccupancy = null
        currentSessionId = "s${System.currentTimeMillis()}"
        setMsg("new session started")
    }

    /** Switch to a saved session: restores the timeline AND the controller's transcript history,
     *  so the agent keeps that conversation's context (re-prefilled on the next turn). */
    private fun handleSelectSession(sessionId: String, setMsg: (String) -> Unit) {
        if (sessionId == currentSessionId) return
        persistCurrentSession()
        val items = sessionStore.load(sessionId)
        val turns = mutableListOf<PromptAssembler.Turn>()
        var pendingUser: String? = null
        for (item in items) {
            when (item.role) {
                TranscriptRole.USER -> pendingUser = item.text
                TranscriptRole.ASSISTANT -> {
                    val user = pendingUser
                    if (user != null && item.text.isNotBlank() && !item.interrupted) {
                        turns += PromptAssembler.Turn(user, item.text)
                        pendingUser = null
                    }
                }
                else -> Unit
            }
        }
        controller.restoreHistory(turns, summary = sessionStore.meta(sessionId)?.summary)
        sessionItems = items
        itemSeq = items.size
        streamingReply = ""
        latencyLine = ""
        ctxOccupancy = null
        currentSessionId = sessionId
        setMsg("session restored (${turns.size} turns of context)")
    }

    /** Delete a saved session; deleting the open one behaves like starting a new session. */
    private fun handleDeleteSession(sessionId: String, setMsg: (String) -> Unit) {
        sessionStore.delete(sessionId)
        if (sessionId == currentSessionId) {
            controller.resetConversation()
            sessionItems = emptyList()
            streamingReply = ""
            latencyLine = ""
            ctxOccupancy = null
            currentSessionId = "s${System.currentTimeMillis()}"
        }
        refreshSessionList()
        setMsg("session deleted")
    }

    @Composable
    private fun Screen() {
        ConversationRoute(
            status = status,
            convState = convState,
            sessionItems = sessionItems,
            streamingReply = streamingReply,
            latencyLine = latencyLine,
            contextOccupancyPercent = ctxOccupancy,
            activeModelName = llmModel.displayName,
            sessions = sessionList,
            currentSessionId = currentSessionId,
            initOk = initOk,
            llmOk = llmOk,
            asrOk = asrOk,
            vadOk = vadOk,
            micGranted = micGranted,
            initialAsrLanguage = asr.language,
            initialBargeIn = initialBargeIn,
            settingsController = settingsController,
            onRequestMicPermission = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
            onNewSession = ::handleNewSession,
            onSelectSession = ::handleSelectSession,
            onDeleteSession = ::handleDeleteSession,
            onSpeak = ::handleSpeak,
            onAskLlm = ::handleAskLlm,
            onConverse = ::handleConverse,
            onChangeLanguage = ::handleChangeAsrLanguage,
            onStartRecording = ::handleStartRecording,
            onStopRecording = ::handleStopRecording,
            onToggleConversation = ::handleToggleConversation,
            onToggleBargeIn = ::handleToggleBargeIn,
            onCancelCurrentTurn = ::handleCancelCurrentTurn,
            onAsrTest = ::handleAsrTest,
        )
    }

    private fun handleSpeak(text: String, setBusy: (Boolean) -> Unit, setMsg: (String) -> Unit) {
        setBusy(true)
        setMsg("synthesizing...")
        lifecycleScope.launch {
            val (pcm, ms) = withContext(Dispatchers.Default) {
                val dbg = com.conversationalai.agent.tts.RawInputs.loadOrNull(
                    java.io.File(filesDir, "dbg_inputs"),
                )
                val inputs = dbg ?: inputBuilder.build(text, lang = "ko")
                if (dbg != null) Log.i(TAG, "using dbg_inputs (host-verified raw)")
                val t0 = System.nanoTime()
                val out = tts.synthesizeClause(inputs, k = 6)
                out to (System.nanoTime() - t0) / 1_000_000
            }
            if (pcm == null) {
                setMsg("synthesis failed (logcat)")
                setBusy(false)
                return@launch
            }
            var peak = 0f
            var sq = 0.0
            for (v in pcm) {
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
                sq += v.toDouble() * v
            }
            val rms = if (pcm.isNotEmpty()) kotlin.math.sqrt(sq / pcm.size) else 0.0
            Log.i(TAG, "synth ${ms}ms samples=${pcm.size} peak=$peak rms=$rms")
            runCatching {
                val f = java.io.File(filesDir, "last_tts.f32")
                java.io.DataOutputStream(f.outputStream().buffered()).use { o ->
                    val bb = java.nio.ByteBuffer.allocate(pcm.size * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(pcm)
                    o.write(bb.array())
                }
                Log.i(TAG, "dumped PCM -> ${f.absolutePath}")
            }.onFailure { Log.e(TAG, "dump failed", it) }
            setMsg("synth ${ms}ms, ${pcm.size} smp, peak=${"%.3f".format(peak)} -> playing")
            withContext(Dispatchers.IO) { PcmPlayer.playMono(pcm) }
            setMsg("done (${ms}ms, peak=${"%.3f".format(peak)})")
            setBusy(false)
        }
    }

    private fun handleAskLlm(
        text: String,
        clearOutput: () -> Unit,
        appendOutput: (String) -> Unit,
        setBusy: (Boolean) -> Unit,
        setMsg: (String) -> Unit,
    ) {
        setBusy(true)
        clearOutput()
        setMsg("LLM...")
        lifecycleScope.launch {
            val prompt = PromptAssembler.chatml(text)
            val t0 = System.nanoTime()
            var ttftMs = 0L
            var chars = 0
            withContext(Dispatchers.Default) {
                // Raw one-shot debug query: keep it OUT of the conversation's persistent KV
                // session (reset around it so the controller re-prefills its own transcript).
                llm.resetSession()
                llm.generate(prompt) { tok ->
                    if (ttftMs == 0L) ttftMs = (System.nanoTime() - t0) / 1_000_000
                    chars += tok.length
                    appendOutput(tok)
                }
                llm.resetSession()
            }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "LLM done TTFT=${ttftMs}ms total=${totalMs}ms chars=$chars")
            setMsg("LLM TTFT=${ttftMs}ms total=${totalMs}ms ($chars chars)")
            setBusy(false)
        }
    }

    private fun handleConverse(
        text: String,
        clearOutput: () -> Unit,
        appendOutput: (String) -> Unit,
        setBusy: (Boolean) -> Unit,
        setMsg: (String) -> Unit,
    ) {
        setBusy(true)
        clearOutput()
        setMsg("conversing...")
        lifecycleScope.launch {
            setMsg(runConversation(text) { appendOutput(it) })
            setBusy(false)
        }
    }

    private fun handleChangeAsrLanguage(
        next: String,
        setAsrLang: (String) -> Unit,
        setBusy: (Boolean) -> Unit,
        setMsg: (String) -> Unit,
    ) {
        setBusy(true)
        setMsg("switching ASR to $next...")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { asr.setLanguage(next) }
            if (ok) {
                setAsrLang(next)
                setMsg("ASR language: $next")
            } else {
                setMsg("ASR language switch failed (see logcat)")
            }
            setBusy(false)
        }
    }

    private fun handleStartRecording(
        setRecording: (Boolean) -> Unit,
        setBusy: (Boolean) -> Unit,
        clearOutput: () -> Unit,
        setMsg: (String) -> Unit,
    ) {
        if (capture.start()) {
            setRecording(true)
            setBusy(true)
            clearOutput()
            setMsg("listening... tap Stop")
        } else {
            setMsg("mic start failed (see logcat)")
        }
    }

    private fun handleStopRecording(
        setRecording: (Boolean) -> Unit,
        setBusy: (Boolean) -> Unit,
        setMsg: (String) -> Unit,
        appendOutput: (String) -> Unit,
    ) {
        setRecording(false)
        setMsg("transcribing...")
        lifecycleScope.launch {
            val (heard, asrMs) = withContext(Dispatchers.Default) {
                val raw = capture.stop()
                if (raw.isEmpty()) return@withContext "" to 0L
                val (clean, sr) = if (enhanceOk) enhancer.enhance(raw, 16000) else raw to 16000
                val t0 = System.nanoTime()
                val t = asr.transcribe(clean, sr)
                t to (System.nanoTime() - t0) / 1_000_000
            }
            Log.i(TAG, "heard (${asrMs}ms): $heard")
            if (heard.isBlank()) {
                setMsg("(heard nothing)")
                setBusy(false)
                return@launch
            }
            setMsg("you: $heard")
            val r = runConversation(heard, asrMs = asrMs) { appendOutput(it) }
            setMsg("you: $heard  |  ASR ${asrMs}ms - $r")
            setBusy(false)
        }
    }

    private fun handleToggleConversation(
        currentConv: Boolean,
        setConv: (Boolean) -> Unit,
        clearConversation: () -> Unit,
    ) {
        if (!currentConv) {
            setConv(true)
            clearConversation()
            // controller.start() begins a FRESH session (history/KV cleared) — mirror that here.
            sessionItems = emptyList()
            streamingReply = ""
            latencyLine = ""
            ctxOccupancy = null
            controller.start()
        } else {
            setConv(false)
            controller.stop()
        }
    }

    private fun handleToggleBargeIn(current: Boolean, setBargeIn: (Boolean) -> Unit) {
        val next = !current
        setBargeIn(next)
        settingsController.setBargeIn(next)   // applies to the controller + persists
    }

    private fun handleCancelCurrentTurn(
        setRecording: (Boolean) -> Unit,
        setConv: (Boolean) -> Unit,
        setBusy: (Boolean) -> Unit,
        setMsg: (String) -> Unit,
    ) {
        capture.stop()
        controller.stop()
        llm.abort()
        setRecording(false)
        setConv(false)
        setBusy(false)
        setMsg("cancelled")
    }

    private fun handleAsrTest(setBusy: (Boolean) -> Unit, setMsg: (String) -> Unit) {
        setBusy(true)
        setMsg("ASR...")
        lifecycleScope.launch {
            val wav = File(filesDir, "asr_test/ko.wav")
            val (txt, ms) = withContext(Dispatchers.Default) {
                val w = com.k2fsa.sherpa.onnx.WaveReader.readWave(wav.absolutePath)
                val t0 = System.nanoTime()
                val t = asr.transcribe(w.samples, w.sampleRate)
                t to (System.nanoTime() - t0) / 1_000_000
            }
            Log.i(TAG, "ASR ${ms}ms -> $txt")
            setMsg("ASR ${ms}ms: $txt")
            setBusy(false)
        }
    }

    override fun onDestroy() {
        if (::eventLogger.isInitialized) eventLogger.log("session.end")
        tts.release()
        if (::llm.isInitialized) llm.release()
        if (::asr.isInitialized) asr.release()
        if (::enhancer.isInitialized) enhancer.release()
        if (::controller.isInitialized) controller.stop()
        super.onDestroy()
    }

    companion object { private const val TAG = "MainActivity" }
}
