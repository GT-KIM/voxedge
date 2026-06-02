package com.conversationalai.agent.core

import android.util.Log
import com.conversationalai.agent.audio.PcmStreamPlayer
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
) {
    suspend fun run(
        gid: Long,
        prompt: String,
        userText: String,
        asrMs: Long,
        onDelta: (String) -> Unit,
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
        withContext(Dispatchers.Default) {
            llm.generate(prompt) { tok ->
                if (!generationEpoch.isCurrent(gid)) return@generate
                if (ttftMs == 0L) {
                    ttftMs = elapsedSince(t0)
                    eventLogger?.log(
                        event = "llm.first_token",
                        generationId = gid,
                        elapsedMs = ttftMs,
                        attributes = mapOf("token_chars" to tok.length),
                    )
                }
                reply.append(tok)
                onDelta(tok)
                seg.accept(tok)
            }
            seg.finish()
            clauses.close()
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
    }
}
