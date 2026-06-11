package com.conversationalai.agent.core

/**
 * Decides, per turn, whether the persistent LLM session (engine KV cache) can be CONTINUED with an
 * incremental prompt ([PromptAssembler.chatmlIncremental]) or must be RE-PREFILLED from the
 * transcript ([PromptAssembler.chatml] after `LlmEngine.resetSession()`).
 *
 * Re-prefill triggers:
 *  - engine not session-capable, or session cold (first turn / after abort, overflow, error);
 *  - the user switched language (the in-KV system prompt pins the reply language);
 *  - context occupancy at/over [MAX_OCCUPANCY_PERCENT] — re-prefilling with the recent-turn
 *    transcript cap is how old turns get evicted, since KV entries can't be dropped piecemeal.
 *
 * Occupancy -1 (engine can't report it) does NOT force a re-prefill: overflow is still caught by
 * the engine's CONTEXT_EXCEEDED result, which colds the session for the next turn.
 *
 * Pure logic, no Android deps — JVM-tested in LlmSessionPolicyTest.
 */
object LlmSessionPolicy {

    /** Re-prefill above this so a long reply still fits comfortably under the context cap. */
    const val MAX_OCCUPANCY_PERCENT = 80

    fun canContinue(
        sessionCapable: Boolean,
        sessionWarm: Boolean,
        occupancyPercent: Int,
        sessionLang: PromptAssembler.Lang?,
        turnLang: PromptAssembler.Lang,
    ): Boolean =
        sessionCapable &&
            sessionWarm &&
            sessionLang == turnLang &&
            occupancyPercent < MAX_OCCUPANCY_PERCENT
}
