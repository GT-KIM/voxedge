package com.conversationalai.agent.core.memory

import java.io.File

/**
 * Durable, on-device key/value memory for the agent — the thing the rolling summary can't be:
 * facts the user wants remembered ACROSS sessions ("my name is Kim", "I take the 8am bus"). The
 * LLM writes via the remember_fact tool and reads via recall_facts; a compact snapshot is also
 * injected into the system prompt so short, stable facts ground every turn without a tool round-trip.
 *
 * Storage is a single newline-delimited file (`key\tvalue` per line, tab/newline escaped), loaded
 * once and rewritten on change — at the few-dozen-facts scale this is simpler and safer than a DB.
 * Keys are normalized (trimmed, lowercased, collapsed whitespace) so "My Name" and "my name" are
 * the same slot. Thread-safe; pure JVM (takes a [File], not a Context) so it is unit-tested directly.
 */
class MemoryStore(private val file: File) {

    private val lock = Any()
    private val facts = LinkedHashMap<String, String>()   // insertion-ordered; newest re-inserted last
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            runCatching {
                if (file.exists()) {
                    file.readLines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEach
                        val k = unescape(line.substring(0, tab))
                        val v = unescape(line.substring(tab + 1))
                        if (k.isNotEmpty()) facts[k] = v
                    }
                }
            }
            loaded = true
        }
    }

    /** Store or overwrite a fact. Returns false for an empty key/value. */
    fun remember(key: String, value: String): Boolean {
        val k = normalizeKey(key)
        val v = value.trim()
        if (k.isEmpty() || v.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            facts.remove(k)        // re-insert so most-recently-set sorts last
            facts[k] = v
            persist()
        }
        return true
    }

    /** Drop a fact. Returns true if it existed. */
    fun forget(key: String): Boolean {
        val k = normalizeKey(key)
        synchronized(lock) {
            ensureLoaded()
            val existed = facts.remove(k) != null
            if (existed) persist()
            return existed
        }
    }

    /** All facts, oldest-first. */
    fun all(): List<Pair<String, String>> {
        synchronized(lock) {
            ensureLoaded()
            return facts.entries.map { it.key to it.value }
        }
    }

    /**
     * Facts whose key or value contains any whitespace-separated term of [query] (case-insensitive).
     * A blank query returns everything. Substring match keeps it forgiving for a small model's
     * paraphrased lookups ("name" finds "my name").
     */
    fun recall(query: String): List<Pair<String, String>> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return all()
        return all().filter { (k, v) ->
            val hay = (k + " " + v).lowercase()
            terms.any { hay.contains(it) }
        }
    }

    /** A compact prompt snapshot: the [limit] most-recently-set facts, one per line. Empty if none. */
    fun promptSnapshot(limit: Int = 12): String {
        val items = all().takeLast(limit)
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { (k, v) -> "- $k: $v" }
    }

    fun size(): Int { synchronized(lock) { ensureLoaded(); return facts.size } }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(facts.entries.joinToString("\n") { (k, v) -> escape(k) + "\t" + escape(v) })
        }
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
