package com.conversationalai.agent.tts

/** Builds static TTS model inputs for one speakable clause. */
interface ClauseInputBuilder {
    fun build(text: String, lang: String = "ko", seed: Long = 0L): TtsInputs
}
