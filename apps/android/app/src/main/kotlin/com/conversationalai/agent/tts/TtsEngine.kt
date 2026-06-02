package com.conversationalai.agent.tts

/**
 * Platform-neutral TTS boundary. The runtime side of `tts.chunk_request -> tts.audio_chunk`
 * (shared/mcp/conversation_events.schema.json).
 */
interface TtsEngine {
    fun version(): String

    /** Synthesize one clause; returns 44.1 kHz mono float PCM, or null on failure. */
    fun synthesizeClause(inputs: TtsInputs, k: Int): FloatArray?
}
