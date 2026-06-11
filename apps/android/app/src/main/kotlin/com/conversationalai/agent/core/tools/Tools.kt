package com.conversationalai.agent.core.tools

/**
 * On-device tool-use contract (the agentic foundation): the LLM requests a tool with a
 * `<tool_call>{json}</tool_call>` block (intercepted by core/ToolCallFilter so it is never
 * spoken), the [ToolRegistry] executes it, and the result is fed back into the SAME warm LLM
 * session as a `<tool_response>` continuation (ChatTemplate.toolResponse) for the next reasoning
 * step. Pure contract — Android tool implementations live in the app's devicetools package.
 */
data class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
)

/** Prompt-facing description of one tool (rendered by PromptAssembler.toolsModule).
 *  [sideEffect] marks tools that change device state — they participate in the confirmation
 *  policy when it is enabled. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val sideEffect: Boolean = false,
)

/** A parsed tool invocation from the model. Argument values are normalized to strings. */
data class ToolCall(
    val name: String,
    val arguments: Map<String, String>,
)

/** [content] is fed back to the model verbatim — keep it short, factual, and plain-text. */
data class ToolResult(
    val ok: Boolean,
    val content: String,
)

interface Tool {
    val spec: ToolSpec

    /** Execute with the model-provided [args]. Must be fast (the speech loop is waiting) and must
     *  not throw — but the registry guards anyway. */
    fun execute(args: Map<String, String>): ToolResult
}

/**
 * Looks up and runs tools; failures become model-readable results, never exceptions.
 *
 * CONFIRMATION POLICY (when [confirmSideEffects] is on): the FIRST call of a side-effecting tool
 * in a turn is not executed — the model is told to ask the user. If the model calls the same tool
 * again on a LATER turn (i.e. after the user replied, presumably confirming), it executes. A
 * pending confirmation expires if the very next turn doesn't re-call the tool (the user declined
 * or moved on). The gate sits here so the prompt-convention loop and engine-native tool calling
 * share it; [beginTurn] is advanced by the conversation controller.
 */
class ToolRegistry(private val tools: List<Tool>) {

    @Volatile var confirmSideEffects: Boolean = false

    private val lock = Any()
    private var currentTurn = 0L
    private var pendingName: String? = null
    private var pendingTurn = 0L

    val specs: List<ToolSpec> get() = tools.map { it.spec }
    val isEmpty: Boolean get() = tools.isEmpty()

    /** Mark the start of a user turn (monotonic id). Expires stale pending confirmations. */
    fun beginTurn(turnId: Long) {
        synchronized(lock) {
            currentTurn = turnId
            if (pendingName != null && turnId > pendingTurn + 1) pendingName = null
        }
    }

    fun dispatch(call: ToolCall): ToolResult {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return ToolResult(
                ok = false,
                content = "unknown tool '${call.name}'; available tools: " +
                    tools.joinToString(", ") { it.spec.name },
            )
        if (confirmSideEffects && tool.spec.sideEffect) {
            synchronized(lock) {
                val confirmed = pendingName == call.name && currentTurn > pendingTurn
                if (!confirmed) {
                    pendingName = call.name
                    pendingTurn = currentTurn
                    return ToolResult(
                        ok = false,
                        content = "CONFIRMATION REQUIRED: '${call.name}' was NOT run. Briefly ask " +
                            "the user to confirm this action, and call the tool again only after " +
                            "they say yes.",
                    )
                }
                pendingName = null
            }
        }
        return runCatching { tool.execute(call.arguments) }
            .getOrElse { e -> ToolResult(false, "tool '${call.name}' failed: ${e.message ?: "error"}") }
    }
}
