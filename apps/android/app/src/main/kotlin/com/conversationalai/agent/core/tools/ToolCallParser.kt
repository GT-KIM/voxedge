package com.conversationalai.agent.core.tools

/**
 * Parses the JSON payload of one `<tool_call>` block:
 *   {"name": "tool_name", "arguments": {"param": "value", "count": 5, "flag": true}}
 *
 * Deliberately a tiny hand-rolled parser: the payload shape is fixed and flat, org.json is an
 * unmockable Android stub in JVM unit tests, and a serialization library is overkill for one
 * object. Tolerant of whitespace/newlines; argument values (string/number/bool/null) are
 * normalized to strings. Returns null on anything that doesn't look like a tool call.
 */
object ToolCallParser {

    fun parse(payload: String): ToolCall? {
        val s = payload.trim()
        if (s.isEmpty()) return null
        val name = stringField(s, "name") ?: return null
        if (name.isEmpty()) return null
        val args = LinkedHashMap<String, String>()
        val objStart = objectStart(s, "arguments")
        if (objStart >= 0) {
            var i = objStart + 1   // past '{'
            while (i < s.length) {
                i = skipWs(s, i)
                if (i >= s.length || s[i] == '}') break
                if (s[i] == ',') { i++; continue }
                if (s[i] != '"') return null
                val (key, afterKey) = readString(s, i) ?: return null
                i = skipWs(s, afterKey)
                if (i >= s.length || s[i] != ':') return null
                i = skipWs(s, i + 1)
                val (value, afterValue) = readValue(s, i) ?: return null
                args[key] = value
                i = afterValue
            }
        }
        return ToolCall(name, args)
    }

    /** Value of a top-level string field ("name"). */
    private fun stringField(s: String, field: String): String? {
        var i = 0
        while (true) {
            i = s.indexOf("\"$field\"", i)
            if (i < 0) return null
            var j = skipWs(s, i + field.length + 2)
            if (j < s.length && s[j] == ':') {
                j = skipWs(s, j + 1)
                if (j < s.length && s[j] == '"') return readString(s, j)?.first
            }
            i += field.length + 2
        }
    }

    /** Index of the '{' opening the object value of [field], or -1. */
    private fun objectStart(s: String, field: String): Int {
        var i = s.indexOf("\"$field\"")
        if (i < 0) return -1
        i = skipWs(s, i + field.length + 2)
        if (i >= s.length || s[i] != ':') return -1
        i = skipWs(s, i + 1)
        return if (i < s.length && s[i] == '{') i else -1
    }

    /** Reads the JSON string starting at the opening quote [i]; returns (unescaped, indexAfter). */
    private fun readString(s: String, i: Int): Pair<String, Int>? {
        if (s[i] != '"') return null
        val sb = StringBuilder()
        var j = i + 1
        while (j < s.length) {
            when (val c = s[j]) {
                '\\' -> {
                    if (j + 1 >= s.length) return null
                    when (val e = s[j + 1]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'u' -> {
                            if (j + 5 >= s.length) return null
                            sb.append(s.substring(j + 2, j + 6).toInt(16).toChar())
                            j += 4
                        }
                        else -> sb.append(e)
                    }
                    j += 2
                }
                '"' -> return sb.toString() to j + 1
                else -> { sb.append(c); j++ }
            }
        }
        return null
    }

    /** Reads a string/number/bool/null value at [i]; returns (asString, indexAfter). */
    private fun readValue(s: String, i: Int): Pair<String, Int>? {
        if (i >= s.length) return null
        if (s[i] == '"') return readString(s, i)
        var j = i
        while (j < s.length && s[j] !in ",}\n\r\t ") j++
        val raw = s.substring(i, j)
        return when {
            raw.isEmpty() -> null
            raw == "true" || raw == "false" || raw == "null" -> raw to j
            raw.toDoubleOrNull() != null -> raw to j
            else -> null
        }
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }
}
