package com.conversationalai.agent.core

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * App-private JSONL event log for demo evidence and latency forensics.
 *
 * The logger intentionally has no Android dependencies so it can be unit-tested on the JVM. It
 * writes one compact JSON object per line and never uploads anything.
 */
class RuntimeEventLogger(
    private val outputFile: File,
    private val clock: Clock = SystemClock,
) {
    interface Clock {
        fun wallMs(): Long
        fun monoMs(): Long
    }

    private object SystemClock : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun monoMs(): Long = System.nanoTime() / 1_000_000
    }

    private val seq = AtomicLong(0)
    private val lock = Any()

    fun log(
        event: String,
        generationId: Long? = null,
        elapsedMs: Long? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val fields = linkedMapOf<String, Any?>(
            "schema_version" to SCHEMA_VERSION,
            "seq" to seq.getAndIncrement(),
            "event" to event,
            "t_wall_ms" to clock.wallMs(),
            "t_mono_ms" to clock.monoMs(),
        )
        if (generationId != null) fields["generation_id"] = generationId
        if (elapsedMs != null) fields["elapsed_ms"] = elapsedMs
        for ((key, value) in attributes) fields[key] = value

        synchronized(lock) {
            outputFile.parentFile?.mkdirs()
            outputFile.appendText(toJson(fields) + "\n")
        }
    }

    companion object {
        const val SCHEMA_VERSION = "runtime-log-v1"

        private fun toJson(fields: Map<String, Any?>): String =
            fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                quote(key) + ":" + jsonValue(value)
            }

        private fun jsonValue(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
            else -> quote(value.toString())
        }

        private fun quote(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            out.append("\\u")
                            out.append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            out.append(ch)
                        }
                    }
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}
