package com.conversationalai.agent.llm

/** Runtime-adjustable sampling parameters. The Genie bundle ships temp=0.7/top-k=20/top-p=0.8;
 *  the app default lowers temp to 0.6 for more grounded spoken answers (review recommendation:
 *  try 0.4-0.6 before judging 4B answer quality). */
data class LlmSampling(
    val temp: Float = 0.6f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
)

/**
 * Platform-neutral LLM boundary. Phase 3 uses GenieLlm (libGenie.so via JNI) with token streaming.
 *
 * Engines MAY keep a persistent session (KV cache) across [generate] calls. When
 * [sessionCapable] is true and [sessionWarm] returns true, the caller sends ONLY the incremental
 * continuation (the new user turn) instead of replaying the whole transcript; the engine's KV
 * cache already holds the system prompt and earlier turns. The session-vs-reprefill decision is
 * policy, owned by the caller (see core/LlmSessionPolicy).
 */
interface LlmEngine {

    /** Outcome of one [generate] call. Anything but OK means the engine's session (if any) no
     *  longer matches the conversation transcript and must be re-prefilled. */
    enum class Result { OK, CONTEXT_EXCEEDED, ABORTED, ERROR }

    fun name(): String

    /**
     * Stream the assistant response for an already-formatted prompt (see [PromptAssembler]),
     * delivering incremental text chunks to [onToken] as they decode. Blocks until generation
     * ends; call it off the main thread. For session-capable engines the prompt is APPENDED to
     * the live session (KV cache), so a warm-session caller passes only the new turn.
     */
    fun generate(prompt: String, onToken: (String) -> Unit): Result

    /** Signal an in-flight [generate] to stop early. No-op if the engine is idle. */
    fun abort()

    /** Whether this engine keeps KV state across [generate] calls. */
    val sessionCapable: Boolean get() = false

    /** True when the engine holds a valid session that ended with a clean [Result.OK] turn.
     *  False when cold, after [resetSession], or after an abort/overflow/error. */
    fun sessionWarm(): Boolean = false

    /** Drop the engine's accumulated session state (KV cache). Cheap; safe to call when idle. */
    fun resetSession() {}

    /** Percent (0..100) of the engine context window currently occupied, or -1 if unknown. */
    fun contextOccupancyPercent(): Int = -1

    /** Apply new sampling parameters; returns false if unsupported or the engine rejected them. */
    fun setSampling(sampling: LlmSampling): Boolean = false

    /**
     * Chat-format id the caller renders prompts with (see core/ChatTemplate): "chatml", "gemma",
     * or "raw" for engines that apply their own template and expect plain user text.
     */
    fun chatTemplateId(): String = "chatml"

    /** System prompt for engines that own templating/history ("raw"): applied when the session is
     *  next (re)created. Template-driven engines receive the system prompt inline and ignore this. */
    fun setSystemPrompt(systemPrompt: String) {}

    /** Free engine resources. Safe to call more than once. */
    fun release() {}
}
