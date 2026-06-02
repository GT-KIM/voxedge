package com.conversationalai.agent.llm

/**
 * Platform-neutral LLM boundary. Phase 3 uses GenieLlm (libGenie.so via JNI) with token streaming.
 */
interface LlmEngine {
    fun name(): String

    /**
     * Stream the assistant response for an already-formatted prompt (see [PromptAssembler]),
     * delivering incremental text chunks to [onToken] as they decode. Blocks until generation
     * ends; call it off the main thread.
     */
    fun generate(prompt: String, onToken: (String) -> Unit)

    /** Signal an in-flight [generate] to stop early. No-op if the engine is idle. */
    fun abort()
}
