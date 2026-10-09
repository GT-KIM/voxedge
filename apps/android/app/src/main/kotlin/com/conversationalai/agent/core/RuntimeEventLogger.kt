package com.conversationalai.agent.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * App-private JSONL event log for demo evidence and latency forensics.
 *
 * The logger intentionally has no Android dependencies so it can be unit-tested on the JVM. It
 * writes one compact JSON object per line and never uploads anything.
 *
 * Writes are ASYNCHRONOUS: [log] only serializes the event and queues the line; a single daemon
 * writer thread appends it through one persistently open buffered stream and flushes whenever the
 * queue drains. The hot paths that log (the LLM token callback, the TTS consumer, the MCP taps
 * around every clause) therefore never pay for a file open/write/close - previously ~1-3 ms per
 * clause on the device - and [seq] still reflects the order events were logged in. [flush] waits
 * for everything queued so far to reach the file (tests, session end).
 *
 * The file is rotated to `<name>.1` (replacing any previous one) once it exceeds [maxBytes], so
 * a long-lived install cannot grow the log without bound.
 */
class RuntimeEventLogger(
    private val outputFile: File,
    private val clock: Clock = SystemClock,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    interface Clock {
        fun wallMs(): Long
        fun monoMs(): Long
    }

    private object SystemClock : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun monoMs(): Long = System.nanoTime() / 1_000_000
    }

    /** The rotated-out previous log (`<name>.1`), if any. */
    val rotatedFile: File get() = File(outputFile.parentFile, outputFile.name + ".1")

    private val seq = AtomicLong(0)
    private val queue = LinkedBlockingQueue<Any>()   // String lines or Flush markers
    private val flushMarker = Any()
    @Volatile private var closed = false
    private var writer: BufferedWriter? = null
    private var bytesWritten = -1L
    private val flushLock = Object()
    private var flushesServed = 0L
    private var flushesRequested = 0L
    private val thread = Thread({ drainLoop() }, "runtime-event-log").apply {
        isDaemon = true
        start()
    }

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
        if (closed) return
        queue.offer(toJson(fields) + "\n")
    }

    /** Block until every event logged before this call is written and flushed to the file. */
    fun flush() {
        if (closed) return
        val ticket: Long
        synchronized(flushLock) { flushesRequested += 1; ticket = flushesRequested }
        queue.offer(flushMarker)
        synchronized(flushLock) {
            while (flushesServed < ticket && !closed) flushLock.wait(50L)
        }
    }

    /** Flush and stop the writer; later [log] calls are dropped. */
    fun close() {
        if (closed) return
        flush()
        closed = true
        queue.offer(flushMarker)   // wake the writer so it exits
        thread.join(2_000L)
    }

    private fun drainLoop() {
        while (!closed) {
            val first = try { queue.take() } catch (_: InterruptedException) { break }
            val batch = ArrayList<Any>()
            batch.add(first)
            queue.drainTo(batch)
            var flushRequested = false
            for (item in batch) {
                if (item === flushMarker) flushRequested = true
                else writeLine(item as String)
            }
            // Flush whenever the queue drains: the file stays current without one flush per line.
            runCatching { writer?.flush() }
            if (flushRequested) {
                synchronized(flushLock) { flushesServed = flushesRequested; flushLock.notifyAll() }
            }
        }
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }

    private fun writeLine(line: String) {
        try {
            if (bytesWritten < 0) {
                outputFile.parentFile?.mkdirs()
                bytesWritten = if (outputFile.exists()) outputFile.length() else 0L
            }
            if (bytesWritten >= maxBytes) rotate()
            val w = writer ?: openWriter().also { writer = it }
            w.write(line)
            bytesWritten += line.length   // ASCII-dominated; an undercount only delays rotation
        } catch (_: Exception) {
            // Logging must never take the loop down. Drop the line and retry the stream next time.
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile, true), Charsets.UTF_8), 16 * 1024)

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        val old = rotatedFile
        if (old.exists()) old.delete()
        outputFile.renameTo(old)
        bytesWritten = 0L
    }

    companion object {
        const val SCHEMA_VERSION = "runtime-log-v1"
        /** Rotation threshold: ~1 MB per 30 sessions on the device, so this is years of use. */
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024

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
