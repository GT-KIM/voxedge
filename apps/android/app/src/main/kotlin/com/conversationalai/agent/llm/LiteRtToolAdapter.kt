package com.conversationalai.agent.llm

import com.google.ai.edge.litertlm.OpenApiTool
import com.conversationalai.agent.core.tools.ToolCall
import com.conversationalai.agent.core.tools.ToolCallParser
import com.conversationalai.agent.core.tools.ToolRegistry
import com.conversationalai.agent.core.tools.ToolSpec

/**
 * Bridges the app's [ToolRegistry] into LiteRT-LM NATIVE function calling: each [ToolSpec]
 * becomes an [OpenApiTool] (declaration JSON + execute callback) that the runtime invokes itself
 * during generation (`automaticToolCalling`). The engine handles call formatting/parsing in the
 * model's trained format, so the prompt-convention `[TOOL_CALL]` path is skipped entirely for
 * engines using this. Dispatch still goes through [ToolRegistry.dispatch], so the confirmation
 * gate applies to native calls too.
 */
object LiteRtToolAdapter {

    fun providers(registry: ToolRegistry) =
        registry.specs.map { spec -> com.google.ai.edge.litertlm.tool(BridgedTool(spec, registry)) }

    /** Gemini/OpenAPI-style function declaration for one [ToolSpec]. Pure; JVM-tested. */
    fun declarationJson(spec: ToolSpec): String {
        val sb = StringBuilder()
        sb.append("{\"name\": ").append(quote(spec.name))
        sb.append(", \"description\": ").append(quote(spec.description))
        sb.append(", \"parameters\": {\"type\": \"object\", \"properties\": {")
        spec.params.joinTo(sb, ", ") { p ->
            quote(p.name) + ": {\"type\": \"string\", \"description\": " + quote(p.description) + "}"
        }
        sb.append("}")
        val required = spec.params.filter { it.required }
        if (required.isNotEmpty()) {
            sb.append(", \"required\": [")
            required.joinTo(sb, ", ") { quote(it.name) }
            sb.append("]")
        }
        sb.append("}}")
        return sb.toString()
    }

    /** Parse the runtime's argument JSON (a flat object) into our string map. Reuses the tested
     *  tool-call parser by wrapping the object into a full call payload. Pure; JVM-tested. */
    fun parseArgs(name: String, argsJson: String): ToolCall {
        val trimmed = argsJson.trim().ifEmpty { "{}" }
        val parsed = ToolCallParser.parse("{\"name\": ${quote(name)}, \"arguments\": $trimmed}")
        return parsed ?: ToolCall(name, emptyMap())
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private class BridgedTool(
        private val spec: ToolSpec,
        private val registry: ToolRegistry,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = declarationJson(spec)

        override fun execute(args: String): String {
            val result = registry.dispatch(parseArgs(spec.name, args))
            android.util.Log.i("LiteRtToolAdapter", "native tool ${spec.name}($args) -> ok=${result.ok}")
            return result.content
        }
    }
}
