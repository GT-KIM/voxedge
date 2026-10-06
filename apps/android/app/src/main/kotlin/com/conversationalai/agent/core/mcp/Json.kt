package com.conversationalai.agent.core.mcp

/**
 * Minimal JSON codec for the MCP / JSON-RPC envelope.
 *
 * Values map to plain JVM types: object -> LinkedHashMap<String, Any?>, array -> List<Any?>,
 * string -> String, number -> Long (integral) or Double, true/false -> Boolean, null -> null.
 * Hand-rolled on purpose: org.json is a throwing stub in JVM unit tests and the envelope is small.
 */
object Json {

    class ParseException(message: String) : RuntimeException(message)

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("trailing characters at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("expected a JSON object")

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(value: Any?): List<Any?>? = value as? List<Any?>

    fun str(value: Any?): String? = value as? String

    fun long(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else null
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Float -> writeDouble(out, value.toDouble())
            is Double -> writeDouble(out, value)
            is String -> quote(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, v)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            else -> quote(out, value.toString())
        }
    }

    private fun writeDouble(out: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) out.append("null") else out.append(d)
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw ParseException("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("expected string key at $pos")
                val key = string()
                skipWs()
                if (peek() != ':') throw ParseException("expected ':' at $pos")
                pos++
                out[key] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun string(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        if (pos + 1 >= s.length) throw ParseException("bad escape at $pos")
                        when (val e = s[pos + 1]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 5 >= s.length) throw ParseException("bad \\u escape at $pos")
                                sb.append(s.substring(pos + 2, pos + 6).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape '\\$e' at $pos")
                        }
                        pos += 2
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            throw ParseException("unterminated string")
        }

        private fun number(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull() ?: throw ParseException("bad number '$raw' at $start")
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return v
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
    }
}
