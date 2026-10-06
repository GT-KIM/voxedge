package com.conversationalai.agent.asr

/**
 * Routes the conversation loop to one of two [AsrEngine]s: the owned offline sherpa-onnx engine
 * (default, the product path) or the platform recognizer (test option, `PlatformAsr`). The
 * selection is in-memory only — it resets to the owned engine on every launch — because the
 * platform path is for comparison runs, not for the offline product claim.
 */
class SwitchableAsr(
    private val owned: AsrEngine,
    private val platform: AsrEngine?,
) : AsrEngine {

    @Volatile var usePlatform: Boolean = false
        private set

    val active: AsrEngine get() = if (usePlatform && platform != null) platform else owned

    override fun name(): String = active.name()

    override fun transcribe(samples: FloatArray, sampleRate: Int): String =
        active.transcribe(samples, sampleRate)

    /** Switch engines. Returns false (and stays on the owned engine) when no platform engine
     *  was provided. The caller gates on availability before flipping this. */
    fun selectPlatform(enabled: Boolean): Boolean {
        if (enabled && platform == null) {
            usePlatform = false
            return false
        }
        usePlatform = enabled
        return true
    }
}
