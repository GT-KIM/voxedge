import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_SRC = ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin"
CORE = ANDROID_SRC / "com" / "conversationalai" / "agent" / "core"
audio = ANDROID_SRC / "com" / "conversationalai" / "agent" / "audio"
ASR = ANDROID_SRC / "com" / "conversationalai" / "agent" / "asr"
LLM = ANDROID_SRC / "com" / "conversationalai" / "agent" / "llm"
TTS = ANDROID_SRC / "com" / "conversationalai" / "agent" / "tts"
UI = ANDROID_SRC / "com" / "conversationalai" / "agent" / "ui"


class AndroidControllerContractTest(unittest.TestCase):
    def test_controller_depends_on_engine_interfaces(self):
        source = (CORE / "ConversationController.kt").read_text(encoding="utf-8")

        self.assertIn("import com.conversationalai.agent.asr.AsrEngine", source)
        self.assertIn("import com.conversationalai.agent.llm.LlmEngine", source)
        self.assertIn("import com.conversationalai.agent.tts.TtsEngine", source)
        self.assertNotIn("import com.conversationalai.agent.asr.OfflineAsr", source)
        self.assertNotIn("import com.conversationalai.agent.llm.GenieLlm", source)
        self.assertNotIn("import com.conversationalai.agent.tts.SupertonicTts", source)

        constructor = re.search(r"class ConversationController\((.*?)\)\s*\{", source, re.S)
        self.assertIsNotNone(constructor)
        params = constructor.group(1)
        self.assertRegex(params, r"private val asr:\s*AsrEngine")
        self.assertRegex(params, r"private val llm:\s*LlmEngine")
        self.assertRegex(params, r"private val tts:\s*TtsEngine")

    def test_turn_execution_is_extracted_from_controller(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        runner = (CORE / "SpeechTurnRunner.kt").read_text(encoding="utf-8")

        self.assertIn("private val turnRunner = SpeechTurnRunner(", controller)
        self.assertIn("turnRunner.run(gid, prompt, userText, asrMs, onDelta, template, toolsEnabled)", controller)
        self.assertNotIn("val clauses = Channel<String>", controller)
        self.assertNotIn("val seg = ClauseSegmenter", controller)
        self.assertNotIn("player.write(pcm)", controller)

        self.assertIn("class SpeechTurnRunner(", runner)
        self.assertIn("val clauses = Channel<ClauseChunk>(Channel.UNLIMITED)", runner)
        self.assertIn("val seg = ClauseSegmenter", runner)
        # The agentic tool loop generates per step (stepPrompt = initial prompt, then tool
        # responses); single-step turns still pass the initial prompt through unchanged.
        self.assertIn("llm.generate(stepPrompt)", runner)
        self.assertIn("tts.synthesizeClause", runner)
        self.assertIn("player.write(pcm)", runner)
        self.assertIn("private data class ClauseChunk", runner)

    def test_streaming_player_is_behind_interface_factory(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        player_interface = (audio / "PcmStreamPlayer.kt").read_text(encoding="utf-8")
        streaming_player = (audio / "StreamingPcmPlayer.kt").read_text(encoding="utf-8")

        self.assertIn("interface PcmStreamPlayer", player_interface)
        self.assertIn("fun start()", player_interface)
        self.assertIn("fun write(pcm: FloatArray)", player_interface)
        self.assertIn("fun interrupt()", player_interface)
        self.assertIn("fun stopAndRelease()", player_interface)
        self.assertIn("class StreamingPcmPlayer(private val sampleRate: Int = 44100) : PcmStreamPlayer", streaming_player)

        self.assertIn("private val playerFactory: () -> PcmStreamPlayer", controller)
        self.assertIn("@Volatile private var activePlayer: PcmStreamPlayer?", controller)

    def test_generation_epoch_encapsulates_cancellation(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        runner = (CORE / "SpeechTurnRunner.kt").read_text(encoding="utf-8")
        epoch = (CORE / "GenerationEpoch.kt").read_text(encoding="utf-8")

        self.assertIn("class GenerationEpoch", epoch)
        self.assertIn("fun next(): Long", epoch)
        self.assertIn("fun cancel(): Long", epoch)
        self.assertIn("fun isCurrent(generationId: Long): Boolean", epoch)

        self.assertIn("private val generationEpoch = GenerationEpoch()", controller)
        self.assertIn("generationEpoch.cancel()", controller)
        self.assertIn("generationEpoch.next()", controller)
        self.assertNotIn("AtomicLong", controller)
        self.assertNotIn("generationId.get()", controller)

        self.assertIn("private val generationEpoch: GenerationEpoch", runner)
        self.assertIn("generationEpoch.isCurrent(gid)", runner)
        self.assertNotIn("isCurrentGeneration", runner)

    def test_active_player_is_cleared_by_identity(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        runner = (CORE / "SpeechTurnRunner.kt").read_text(encoding="utf-8")

        self.assertIn("onPlayerStarted = { activePlayer = it }", controller)
        self.assertIn("onPlayerStopped = { player -> if (activePlayer === player) activePlayer = null }", controller)
        self.assertIn("private val onPlayerStarted: (PcmStreamPlayer) -> Unit", runner)
        self.assertIn("private val onPlayerStopped: (PcmStreamPlayer) -> Unit", runner)
        self.assertIn("onPlayerStarted(player)", runner)
        self.assertIn("onPlayerStopped(player)", runner)

    def test_controller_uses_state_machine(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        state_machine = (CORE / "SpeechLoopStateMachine.kt").read_text(encoding="utf-8")

        self.assertIn("class SpeechLoopStateMachine", state_machine)
        self.assertIn("fun transitionTo(next: ConvState): ConvState", state_machine)
        self.assertIn("fun canTransition(from: ConvState, to: ConvState): Boolean", state_machine)
        self.assertIn("private val stateMachine = SpeechLoopStateMachine()", controller)
        self.assertIn("private val state: ConvState get() = stateMachine.current", controller)
        self.assertIn("stateMachine.transitionTo(s)", controller)
        self.assertNotIn("@Volatile private var state = ConvState.IDLE", controller)

    def test_engine_interfaces_cover_controller_operations(self):
        asr = (ASR / "AsrEngine.kt").read_text(encoding="utf-8")
        llm = (LLM / "LlmEngine.kt").read_text(encoding="utf-8")
        tts = (TTS / "TtsEngine.kt").read_text(encoding="utf-8")

        self.assertIn("fun transcribe(samples: FloatArray, sampleRate: Int): String", asr)
        self.assertIn("fun generate(prompt: String, onToken: (String) -> Unit)", llm)
        self.assertIn("fun abort()", llm)
        self.assertIn("fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray?", tts)

    def test_tts_input_builder_is_fakeable(self):
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        runner = (CORE / "SpeechTurnRunner.kt").read_text(encoding="utf-8")
        builder_interface = (TTS / "ClauseInputBuilder.kt").read_text(encoding="utf-8")
        builder = (TTS / "TtsInputBuilder.kt").read_text(encoding="utf-8")

        self.assertIn("interface ClauseInputBuilder", builder_interface)
        self.assertIn("fun build(text: String, lang: String = \"ko\", seed: Long = 0L): TtsInputs", builder_interface)
        self.assertIn("class TtsInputBuilder(context: Context, voice: String = \"F1\") : ClauseInputBuilder", builder)
        self.assertIn("override fun build(text: String, lang: String, seed: Long): TtsInputs", builder)
        self.assertIn("private val inputBuilder: ClauseInputBuilder", controller)
        self.assertIn("private val inputBuilder: ClauseInputBuilder", runner)

    def test_main_activity_delegates_runtime_initialization(self):
        main = (UI / "MainActivity.kt").read_text(encoding="utf-8")
        initializer = (UI / "RuntimeInitializer.kt").read_text(encoding="utf-8")

        self.assertIn("class RuntimeInitializer(private val context: Context)", initializer)
        self.assertIn("data class Result(", initializer)
        self.assertIn("fun initialize(): Result", initializer)
        self.assertIn("private fun configureDspEnvironment()", initializer)
        # Multi-backend LLM init (2026-06-10): the initializer selects the persisted LlmCatalog
        # model and returns whichever engine (Genie / LiteRT-LM) it constructed.
        self.assertIn("private fun selectLlmModel(): LlmModelSpec", initializer)
        self.assertIn("private fun initializeLlm(spec: LlmModelSpec): Pair<LlmEngine, Boolean>", initializer)
        self.assertIn("private fun initializeAsr(asr: OfflineAsr): Boolean", initializer)
        self.assertIn("private fun initializeEnhancer(enhancer: SpeechEnhancer): Boolean", initializer)

        self.assertIn("val runtime = RuntimeInitializer(this).initialize()", main)
        self.assertNotIn("DspAssets.materialize", main)
        self.assertNotIn("Os.setenv", main)
        self.assertNotIn("val llmBundle = File(filesDir, \"llm_bundle\")", main)
        self.assertNotIn("val asrModel = File(filesDir, \"asr/model.int8.onnx\")", main)
        self.assertNotIn("val denoiseModel = File(filesDir, \"asr_denoiser/gtcrn_simple.onnx\")", main)

    def test_main_activity_delegates_screen_rendering(self):
        main = (UI / "MainActivity.kt").read_text(encoding="utf-8")
        screen = (UI / "ConversationScreen.kt").read_text(encoding="utf-8")
        route = (UI / "ConversationRoute.kt").read_text(encoding="utf-8")

        self.assertIn("fun ConversationScreen(", screen)
        self.assertIn("state: ConversationUiState", screen)
        self.assertIn("onAction: (ConversationAction) -> Unit", screen)
        self.assertIn("ConversationRoute(", main)
        self.assertIn("ConversationScreen(state = uiState)", route)
        self.assertIn("DebugRuntimePanel", screen)
        self.assertIn("ConversationAction.RunDebugSpeak", screen)
        self.assertIn("ConversationAction.RunDebugAskLlm", screen)
        self.assertIn("ConversationAction.RunDebugConverse", screen)
        self.assertIn("ConversationAction.RunDebugAsrTest", screen)
        self.assertIn("Column(", screen)
        self.assertIn("OutlinedTextField(", screen)
        self.assertNotIn("Column(", main)
        self.assertNotIn("OutlinedTextField(", main)

    def test_main_activity_screen_delegates_action_work(self):
        main = (UI / "MainActivity.kt").read_text(encoding="utf-8")
        screen_fn = re.search(
            r"@Composable\s+private fun Screen\(\) \{(.*?)\n    private fun handleSpeak",
            main,
            re.S,
        )
        self.assertIsNotNone(screen_fn)
        screen_body = screen_fn.group(1)

        for name in [
            "handleSpeak",
            "handleAskLlm",
            "handleConverse",
            "handleChangeAsrLanguage",
            "handleStartRecording",
            "handleStopRecording",
            "handleToggleConversation",
            "handleToggleBargeIn",
            "handleCancelCurrentTurn",
            "handleAsrTest",
        ]:
            self.assertIn(f"private fun {name}", main)

        for name in [
            "onSpeak = ::handleSpeak",
            "onAskLlm = ::handleAskLlm",
            "onConverse = ::handleConverse",
            "onChangeLanguage = ::handleChangeAsrLanguage",
            "onStartRecording = ::handleStartRecording",
            "onStopRecording = ::handleStopRecording",
            "onToggleConversation = ::handleToggleConversation",
            "onToggleBargeIn = ::handleToggleBargeIn",
            "onCancelCurrentTurn = ::handleCancelCurrentTurn",
            "onAsrTest = ::handleAsrTest",
        ]:
            self.assertIn(name, screen_body)

        self.assertNotIn("withContext(", screen_body)
        self.assertNotIn("tts.synthesizeClause", screen_body)
        self.assertNotIn("llm.generate", screen_body)
        self.assertNotIn("asr.transcribe", screen_body)
        self.assertNotIn("PcmPlayer.playMono", screen_body)

    def test_conversation_screen_uses_state_action_boundary(self):
        screen = (UI / "ConversationScreen.kt").read_text(encoding="utf-8")
        state = (UI / "ConversationUiState.kt").read_text(encoding="utf-8")
        action = (UI / "ConversationAction.kt").read_text(encoding="utf-8")
        fixtures = (UI / "ConversationUiStateFixtures.kt").read_text(encoding="utf-8")

        self.assertIn("state: ConversationUiState", screen)
        self.assertIn("onAction: (ConversationAction) -> Unit", screen)
        self.assertNotIn("text: String", screen)
        self.assertNotIn("initOk: Boolean", screen)
        self.assertIn("data class ConversationUiState", state)
        self.assertIn("sealed interface ConversationAction", action)

        for fixture in ["ready", "missingAssets", "listening", "generating", "speaking", "bargeIn", "error"]:
            self.assertIn(f"fun {fixture}()", fixtures)

    def test_runtime_event_logger_is_wired_into_turn_pipeline(self):
        logger = (CORE / "RuntimeEventLogger.kt").read_text(encoding="utf-8")
        controller = (CORE / "ConversationController.kt").read_text(encoding="utf-8")
        runner = (CORE / "SpeechTurnRunner.kt").read_text(encoding="utf-8")
        main = (UI / "MainActivity.kt").read_text(encoding="utf-8")

        self.assertIn("class RuntimeEventLogger(", logger)
        self.assertIn("SCHEMA_VERSION = \"runtime-log-v1\"", logger)
        self.assertIn("outputFile.appendText(toJson(fields) + \"\\n\")", logger)

        self.assertIn("private val eventLogger: RuntimeEventLogger? = null", controller)
        self.assertIn("eventLogger = eventLogger", controller)
        self.assertIn("event = \"turn.start\"", controller)
        self.assertIn("event = \"asr.final\"", controller)
        self.assertIn("event = \"prompt.assembled\"", controller)
        self.assertIn("eventLogger?.log(\"state.changed\"", controller)

        for event in [
            "llm.generate_start",
            "llm.first_token",
            "tts.chunk_request",
            "tts.first_pcm",
            "tts.audio_chunk",
            "playback.start",
            "playback.end",
            "turn.end",
        ]:
            self.assertIn(f"event = \"{event}\"", runner)

        self.assertIn("RuntimeEventLogger(File(filesDir, \"runtime_logs/turn_events.jsonl\"))", main)
        self.assertIn("event = \"session.start\"", main)
        self.assertIn("eventLogger.log(\"session.end\")", main)


if __name__ == "__main__":
    unittest.main()
