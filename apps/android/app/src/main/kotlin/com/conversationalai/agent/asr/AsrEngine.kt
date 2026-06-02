package com.conversationalai.agent.asr

/**
 * Platform-neutral ASR boundary. The current Android runtime uses an owned sherpa-onnx
 * OfflineRecognizer implementation behind this interface, so the app does not depend on Android
 * SpeechRecognizer or a cloud ASR endpoint for the offline product path.
 *
 * Streaming partial/final callbacks can be added later behind this same boundary.
 */
interface AsrEngine {
    fun name(): String

    /** Transcribe one mono utterance ([samples] in [-1,1] at [sampleRate]); blocking. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}
