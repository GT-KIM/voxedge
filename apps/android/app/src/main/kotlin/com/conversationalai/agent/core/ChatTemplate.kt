package com.conversationalai.agent.core

/**
 * Per-model chat formats. An engine declares its template id ([com.conversationalai.agent.llm.LlmEngine.chatTemplateId]);
 * the controller renders prompts through the matching entry, so supporting a new model family is
 * adding an id here instead of editing the pipeline. [full] renders a transcript re-prefill for a
 * cold session; [incremental] renders only the new user turn for a warm persistent-KV session
 * (see LlmSessionPolicy).
 */
enum class ChatTemplate(val id: String) {

    /** Qwen / ChatML: `<|im_start|>role ... <|im_end|>` blocks with a dedicated system role. */
    CHATML("chatml") {
        override fun full(system: String, history: List<PromptAssembler.Turn>, user: String): String =
            buildString {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (t in history) {
                    append("<|im_start|>user\n").append(t.user).append("<|im_end|>\n")
                    append("<|im_start|>assistant\n").append(t.assistant).append("<|im_end|>\n")
                }
                append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }

        override fun incremental(user: String): String =
            "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n"

        override fun toolResponse(content: String): String =
            "<|im_start|>user\n<tool_response>\n" + content +
                "\n</tool_response><|im_end|>\n<|im_start|>assistant\n"
    },

    /**
     * Gemma: `<start_of_turn>user|model ... <end_of_turn>` blocks. Gemma has no system role, so
     * the system prompt is folded into the FIRST user turn (the documented Gemma convention).
     * BOS is left to the tokenizer. For template-level backends running a Gemma-family model.
     */
    GEMMA("gemma") {
        override fun full(system: String, history: List<PromptAssembler.Turn>, user: String): String =
            buildString {
                var pendingSystem = system
                fun userText(text: String): String {
                    val merged = if (pendingSystem.isEmpty()) text else pendingSystem + "\n\n" + text
                    pendingSystem = ""
                    return merged
                }
                for (t in history) {
                    append("<start_of_turn>user\n").append(userText(t.user)).append("<end_of_turn>\n")
                    append("<start_of_turn>model\n").append(t.assistant).append("<end_of_turn>\n")
                }
                append("<start_of_turn>user\n").append(userText(user)).append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }

        override fun incremental(user: String): String =
            "<start_of_turn>user\n" + user + "<end_of_turn>\n<start_of_turn>model\n"

        override fun toolResponse(content: String): String =
            "<start_of_turn>user\n<tool_response>\n" + content +
                "\n</tool_response><end_of_turn>\n<start_of_turn>model\n"
    },

    /**
     * Engine-templated input: the runtime owns the chat template AND the conversation history
     * (e.g. LiteRT-LM's stateful Conversation), so it receives plain user text in both modes.
     * The system prompt travels separately via `LlmEngine.setSystemPrompt` at session
     * (re)configuration, and transcript-history replay on re-prefill is not available — the
     * engine restarts from the system prompt alone.
     */
    RAW("raw") {
        override fun full(system: String, history: List<PromptAssembler.Turn>, user: String): String = user
        override fun incremental(user: String): String = user
        override fun toolResponse(content: String): String =
            "<tool_response>\n" + content + "\n</tool_response>"
    };

    abstract fun full(system: String, history: List<PromptAssembler.Turn>, user: String): String
    abstract fun incremental(user: String): String

    /** Continuation that feeds one tool result back into the WARM session for the next agentic
     *  step (the tool-use loop in SpeechTurnRunner). Rendered as a user-role `<tool_response>`
     *  wrapper — the convention Qwen-family templates train with, and unambiguous for the rest. */
    abstract fun toolResponse(content: String): String

    companion object {
        /** Unknown ids fall back to CHATML (the project's first/primary model family). */
        fun fromId(id: String): ChatTemplate = entries.firstOrNull { it.id == id } ?: CHATML
    }
}
