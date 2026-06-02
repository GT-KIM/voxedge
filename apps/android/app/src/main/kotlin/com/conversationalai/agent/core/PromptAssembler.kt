package com.conversationalai.agent.core

/**
 * Builds the Qwen3 ChatML prompt the Genie dialog tokenizes directly (it does NOT apply a chat
 * template itself — genie-t2t-run is fed the fully-formatted string). Multi-turn is done by
 * including recent turns in the prompt (deterministic, capped) rather than relying on persistent
 * Genie KV — easier to bound, test, and port (the JNI resets the dialog after every query).
 */
object PromptAssembler {

    /** Tuned for the speech loop: spoken plain text, helpful but not long-winded (the earlier hard
     *  "1-2 sentences" cap made answers shallow), with light multi-turn awareness. */
    const val DEFAULT_SYSTEM =
        "You are a helpful, friendly voice assistant in an ongoing spoken conversation. " +
            "Reply in the same language as the user, in a natural spoken style — usually two to four " +
            "sentences: enough to be genuinely useful, not long-winded. Use the prior turns for " +
            "context and follow-ups. Do not use emoji, markdown, bullet points, numbered lists, or " +
            "code blocks; output plain sentences only, because your reply is read aloud."

    data class Turn(val user: String, val assistant: String)

    /** Build the prompt: system + [history] (oldest first) + the current [user] turn. */
    fun chatml(user: String, history: List<Turn> = emptyList(), system: String = DEFAULT_SYSTEM): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (t in history) {
            sb.append("<|im_start|>user\n").append(t.user).append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n").append(t.assistant).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
