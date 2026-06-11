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

/** Prompt-facing description of one tool (rendered by PromptAssembler.toolsModule). */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
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

/** Looks up and runs tools; failures become model-readable results, never exceptions. */
class ToolRegistry(private val tools: List<Tool>) {

    val specs: List<ToolSpec> get() = tools.map { it.spec }
    val isEmpty: Boolean get() = tools.isEmpty()

    fun dispatch(call: ToolCall): ToolResult {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return ToolResult(
                ok = false,
                content = "unknown tool '${call.name}'; available tools: " +
                    tools.joinToString(", ") { it.spec.name },
            )
        return runCatching { tool.execute(call.arguments) }
            .getOrElse { e -> ToolResult(false, "tool '${call.name}' failed: ${e.message ?: "error"}") }
    }
}
