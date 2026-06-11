package com.conversationalai.agent.tts

/** Builds static TTS model inputs for one speakable clause. */
interface ClauseInputBuilder {
    fun build(text: String, lang: String = "ko", seed: Long = 0L): TtsInputs

    /** Speech rate stamped into built inputs (settings); no-op default for fakes. */
    fun setSpeed(speed: Float) {}

    /** Switch the speaker voice (style embedding); false if unavailable. */
    fun setVoice(name: String): Boolean = false

    /** Provisioned voice names ("F1", ...); empty when the builder has no voice assets. */
    fun availableVoices(): List<String> = emptyList()
}
