package com.conversationalai.agent.core

import android.util.Log
import com.conversationalai.agent.audio.PcmStreamPlayer
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.llm.LlmEngine
import com.conversationalai.agent.tts.ClauseInputBuilder
import com.conversationalai.agent.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes one assistant turn after ASR text is available:
 * LLM token stream -> clause segmentation -> TTS synthesis -> streamed PCM playback.
 *
 * With a [tools] registry and a session-capable engine the turn becomes a bounded AGENTIC LOOP:
 * each generation step streams through a [ToolCallFilter]; a detected `<tool_call>` is executed
 * (never spoken) and its result is appended to the warm KV session as a `<tool_response>`
 * continuation for the next step, until the model answers in plain text or [MAX_TOOL_STEPS] is
 * hit. Barge-in stays correct: a stale generation id stops the loop BEFORE any tool executes.
 */
class SpeechTurnRunner(
    private val llm: LlmEngine,
    private val tts: TtsEngine,
    private val inputBuilder: ClauseInputBuilder,
    private val playerFactory: () -> PcmStreamPlayer,
    private val generationEpoch: GenerationEpoch,
    private val onPlayerStarted: (PcmStreamPlayer) -> Unit,
    private val onPlayerStopped: (PcmStreamPlayer) -> Unit,
    private val onState: (ConvState) -> Unit,
    private val onSpeakingStarted: () -> Unit,
    private val eventLogger: RuntimeEventLogger? = null,
    private val tools: ToolRegistry? = null,
) {
    suspend fun run(
        gid: Long,
        prompt: String,
        userText: String,
        asrMs: Long,
        onDelta: (String) -> Unit,
        template: ChatTemplate = ChatTemplate.CHATML,
        useTools: Boolean = true,
        rewindFirstStep: Boolean = false,
    ): TurnRecord = coroutineScope {
        val clauses = Channel<ClauseChunk>(Channel.UNLIMITED)
        val player = playerFactory()
        onPlayerStarted(player)
        val reply = StringBuilder()
        val spoken = StringBuilder()
        val t0 = System.nanoTime()
        var ttftMs = 0L
        var firstPcmMs = 0L
        var clauseIndex = 0

        onState(ConvState.GENERATING)
        eventLogger?.log(
            event = "llm.generate_start",
            generationId = gid,
            elapsedMs = 0L,
            attributes = mapOf("prompt_chars" to prompt.length, "user_text_chars" to userText.length),
        )
        val consumer = launch(Dispatchers.Default) {
            player.start()
            var playbackStarted = false
            try {
                for (chunk in clauses) {
                    if (!generationEpoch.isCurrent(gid)) break
                    val synthStart = System.nanoTime()
                    val pcm = runCatching {
                        tts.synthesizeClause(inputBuilder.build(chunk.text, lang = chunk.language), k = 6)
                    }.getOrElse { e ->
                        Log.w(TAG, "clause dropped (${e.message}): \"${chunk.text}\"")
                        eventLogger?.log(
                            event = "tts.chunk_dropped",
                            generationId = gid,
                            elapsedMs = elapsedSince(t0),
                            attributes = mapOf(
                                "chunk_id" to chunk.id,
                                "clause_index" to chunk.index,
                                "error" to (e.message ?: e::class.java.simpleName),
                            ),
                        )
                        null
                    } ?: continue
                    val synthMs = (System.nanoTime() - synthStart) / 1_000_000
                    if (!generationEpoch.isCurrent(gid)) break
                    if (firstPcmMs == 0L) {
                        firstPcmMs = elapsedSince(t0)
                        eventLogger?.log(
                            event = "tts.first_pcm",
                            generationId = gid,
                            elapsedMs = firstPcmMs,
                            attributes = mapOf(
                                "chunk_id" to chunk.id,
                                "clause_index" to chunk.index,
                                "synth_ms" to synthMs,
                                "num_samples" to pcm.size,
                            ),
                        )
                    }
                    eventLogger?.log(
                        event = "tts.audio_chunk",
                        generationId = gid,
                        elapsedMs = elapsedSince(t0),
                        attributes = mapOf(
                            "chunk_id" to chunk.id,
                            "clause_index" to chunk.index,
                            "synth_ms" to synthMs,
                            "num_samples" to pcm.size,
                        ),
                    )
                    if (!playbackStarted) {
                        playbackStarted = true
                        onSpeakingStarted()
                        onState(ConvState.SPEAKING)
                        eventLogger?.log(
                            event = "playback.start",
                            generationId = gid,
                            elapsedMs = elapsedSince(t0),
                            attributes = mapOf("chunk_id" to chunk.id, "clause_index" to chunk.index),
                        )
                    }
                    spoken.append(chunk.text).append(' ')
                    player.write(pcm)
                }
            } finally {
                player.stopAndRelease()
                if (playbackStarted) {
                    eventLogger?.log(
                        event = "playback.end",
                        generationId = gid,
                        elapsedMs = elapsedSince(t0),
                    )
                }
            }
        }

        val seg = ClauseSegmenter(onClause = { clause ->
            val index = clauseIndex++
            val language = LanguageDetector.detect(clause)
            val chunk = ClauseChunk(index = index, id = "c$index", text = clause, language = language)
            eventLogger?.log(
                event = "tts.chunk_request",
                generationId = gid,
                elapsedMs = elapsedSince(t0),
                attributes = mapOf(
                    "chunk_id" to chunk.id,
                    "clause_index" to index,
                    "text_chars" to clause.length,
                    "language" to language,
                ),
            )
            clauses.trySend(chunk)
        })
        // Plain text reaching the user (UI delta + clause segmenter -> TTS). Tool-call JSON is
        // filtered out upstream and never lands here.
        val emitText = { text: String ->
            reply.append(text)
            onDelta(text)
            seg.accept(text)
        }
        // The agentic loop needs a warm session to append tool responses incrementally.
        val toolLoop = useTools && tools != null && !tools.isEmpty && llm.sessionCapable
        val llmResult = withContext(Dispatchers.Default) {
            var stepPrompt = prompt
            var step = 0
            var result: LlmEngine.Result
            while (true) {
                step += 1
                val filter = if (toolLoop) ToolCallFilter(onText = emitText) else null
                val onLlmToken = fun(tok: String) {
                    if (!generationEpoch.isCurrent(gid)) return
                    if (ttftMs == 0L) {
                        ttftMs = elapsedSince(t0)
                        eventLogger?.log(
                            event = "llm.first_token",
                            generationId = gid,
                            elapsedMs = ttftMs,
                            attributes = mapOf("token_chars" to tok.length),
                        )
                    }
                    if (filter != null) filter.accept(tok) else emitText(tok)
                }
                // The first step of a re-prefill turn may rewind: KV prefix-match against the
                // full transcript instead of prefilling from an empty cache.
                result = if (step == 1 && rewindFirstStep) {
                    llm.generateRewind(stepPrompt, onLlmToken)
                } else {
                    llm.generate(stepPrompt, onLlmToken)
                }
                filter?.finish()
                val call = filter?.call
                if (call == null) {
                    // One corrective retry when the model attempted a tool call but the JSON was
                    // malformed/unterminated (small models do this): tell it, on the warm session.
                    if (filter?.malformed == true && step < MAX_TOOL_STEPS &&
                        result == LlmEngine.Result.OK && generationEpoch.isCurrent(gid)
                    ) {
                        eventLogger?.log(
                            event = "tool.malformed_retry",
                            generationId = gid,
                            elapsedMs = elapsedSince(t0),
                            attributes = mapOf("step" to step),
                        )
                        stepPrompt = template.toolResponse(
                            "Your tool call was malformed. Either write one complete valid call " +
                                "as [TOOL_CALL]{\"name\": \"tool_name\", \"arguments\": {...}}" +
                                "[/TOOL_CALL] or answer the user directly in plain text.",
                        )
                        continue
                    }
                    break
                }
                // Never execute tools for a cancelled (barged-in) generation.
                if (result != LlmEngine.Result.OK || !generationEpoch.isCurrent(gid)) break
                if (step >= MAX_TOOL_STEPS) {
                    Log.w(TAG, "tool-step limit hit; dropping call '${call.name}'")
                    eventLogger?.log(
                        event = "tool.step_limit",
                        generationId = gid,
                        elapsedMs = elapsedSince(t0),
                        attributes = mapOf("tool" to call.name, "step" to step),
                    )
                    break
                }
                eventLogger?.log(
                    event = "tool.call",
                    generationId = gid,
                    elapsedMs = elapsedSince(t0),
                    attributes = mapOf("tool" to call.name, "step" to step, "args" to call.arguments.size),
                )
                val toolResult = tools!!.dispatch(call)
                eventLogger?.log(
                    event = "tool.result",
                    generationId = gid,
                    elapsedMs = elapsedSince(t0),
                    attributes = mapOf("tool" to call.name, "ok" to toolResult.ok, "chars" to toolResult.content.length),
                )
                Log.i(TAG, "tool ${call.name}(${call.arguments}) -> ok=${toolResult.ok}")
                stepPrompt = template.toolResponse(toolResult.content)
            }
            seg.finish()
            clauses.close()
            result
        }
        consumer.join()
        onPlayerStopped(player)

        val totalMs = elapsedSince(t0)
        val record = TurnRecord(
            generationId = gid,
            userText = userText,
            replyText = reply.toString(),
            asrMs = asrMs,
            ttftMs = ttftMs,
            firstPcmMs = firstPcmMs,
            totalMs = totalMs,
            bargedIn = !generationEpoch.isCurrent(gid),
            spokenContent = spoken.toString().trim(),
        )
        eventLogger?.log(
            event = "turn.end",
            generationId = gid,
            elapsedMs = totalMs,
            attributes = mapOf(
                "asr_ms" to asrMs,
                "ttft_ms" to ttftMs,
                "first_pcm_ms" to firstPcmMs,
                "total_ms" to totalMs,
                "reply_chars" to record.replyText.length,
                "spoken_chars" to record.spokenContent.length,
                "barged_in" to record.bargedIn,
                "llm_result" to llmResult.name,
            ),
        )
        record
    }

    private data class ClauseChunk(
        val index: Int,
        val id: String,
        val text: String,
        val language: String,
    )

    private fun elapsedSince(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    companion object {
        private const val TAG = "SpeechTurnRunner"
        /** Generation steps per turn (initial + after tool responses). Bounded so a confused
         *  model can't chain tool calls while the user waits in silence. */
        const val MAX_TOOL_STEPS = 3
    }
}
