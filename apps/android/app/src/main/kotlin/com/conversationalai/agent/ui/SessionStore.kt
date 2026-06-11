package com.conversationalai.agent.ui

import java.io.File

/**
 * Persists session transcripts as one JSONL file per session under [dir] (app-private), so the
 * session drawer can list and reopen past conversations. Line 1 is a meta record (title +
 * updated timestamp); each following line is one transcript item with STRING-only fields —
 * serialized and parsed by the tiny flat-object codec below (org.json is an unmockable stub in
 * JVM unit tests, and the shape here doesn't warrant a serialization library).
 */
class SessionStore(private val dir: File) {

    data class SessionMeta(val id: String, val title: String, val updatedMs: Long)

    fun list(): List<SessionMeta> = (dir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray())
        .mapNotNull { file ->
            val first = file.useLines { lines -> lines.firstOrNull() } ?: return@mapNotNull null
            val meta = parseFlatObject(first) ?: return@mapNotNull null
            SessionMeta(
                id = file.nameWithoutExtension,
                title = meta["title"].orEmpty().ifEmpty { "(empty session)" },
                updatedMs = meta["updated_ms"]?.toLongOrNull() ?: file.lastModified(),
            )
        }
        .sortedByDescending { it.updatedMs }

    fun save(id: String, title: String, updatedMs: Long, items: List<TranscriptItem>) {
        dir.mkdirs()
        val sb = StringBuilder()
        sb.append("{\"title\": ").append(quote(title))
            .append(", \"updated_ms\": \"").append(updatedMs).append("\"}\n")
        for (item in items) {
            sb.append("{\"role\": ").append(quote(item.role.name))
                .append(", \"text\": ").append(quote(item.text))
                .append(", \"spoken\": ").append(quote(item.spokenContent))
                .append(", \"meta\": ").append(quote(item.meta))
                .append(", \"interrupted\": ").append(quote(item.interrupted.toString()))
                .append(", \"tools\": ").append(quote(item.tools.joinToString(",")))
                .append("}\n")
        }
        File(dir, "$id.jsonl").writeText(sb.toString())
    }

    fun load(id: String): List<TranscriptItem> {
        val file = File(dir, "$id.jsonl")
        if (!file.isFile) return emptyList()
        return file.readLines().drop(1).mapIndexedNotNull { index, line ->
            val obj = parseFlatObject(line) ?: return@mapIndexedNotNull null
            TranscriptItem(
                id = "r$index",
                role = if (obj["role"] == "USER") TranscriptRole.USER else TranscriptRole.ASSISTANT,
                text = obj["text"].orEmpty(),
                spokenContent = obj["spoken"].orEmpty(),
                meta = obj["meta"].orEmpty(),
                interrupted = obj["interrupted"] == "true",
                tools = obj["tools"].orEmpty().split(",").filter { it.isNotBlank() },
            )
        }
    }

    fun delete(id: String) {
        File(dir, "$id.jsonl").delete()
    }

    companion object {
        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** Parse one flat JSON object whose values are all strings. Null on malformed input. */
        fun parseFlatObject(line: String): Map<String, String>? {
            val s = line.trim()
            if (!s.startsWith("{") || !s.endsWith("}")) return null
            val out = LinkedHashMap<String, String>()
            var i = 1
            while (i < s.length - 1) {
                while (i < s.length && (s[i] == ' ' || s[i] == ',')) i++
                if (i >= s.length - 1) break
                val key = readString(s, i) ?: return null
                i = key.second
                while (i < s.length && (s[i] == ' ' || s[i] == ':')) i++
                val value = readString(s, i) ?: return null
                i = value.second
                out[key.first] = value.first
            }
            return out
        }

        private fun readString(s: String, start: Int): Pair<String, Int>? {
            if (start >= s.length || s[start] != '"') return null
            val sb = StringBuilder()
            var i = start + 1
            while (i < s.length) {
                when (val c = s[i]) {
                    '\\' -> {
                        if (i + 1 >= s.length) return null
                        when (val e = s[i + 1]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(e)
                        }
                        i += 2
                    }
                    '"' -> return sb.toString() to i + 1
                    else -> { sb.append(c); i++ }
                }
            }
            return null
        }
    }
}
