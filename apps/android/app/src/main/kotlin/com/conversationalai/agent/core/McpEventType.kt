package com.conversationalai.agent.core

enum class McpEventType(val wireName: String) {
    SESSION_START("session.start"),
    SESSION_END("session.end"),
    ASR_PARTIAL("asr.partial"),
    ASR_FINAL("asr.final"),
    PROMPT_ASSEMBLED("prompt.assembled"),
    LLM_TEXT_DELTA("llm.text_delta"),
    LLM_TURN_END("llm.turn_end"),
    TTS_CHUNK_REQUEST("tts.chunk_request"),
    TTS_AUDIO_CHUNK("tts.audio_chunk"),
    TTS_PLAYBACK_START("tts.playback_start"),
    TTS_PLAYBACK_END("tts.playback_end"),
    CONTROL_BARGE_IN("control.barge_in"),
    CONTROL_CANCEL("control.cancel"),
    RUNTIME_THERMAL("runtime.thermal"),
    RUNTIME_DEGRADE("runtime.degrade"),
    ERROR("error"),
}
