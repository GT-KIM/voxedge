package com.conversationalai.agent.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEventLoggerTest {
    @Test
    fun writesJsonLinesWithStableFieldsAndEscaping() {
        val file = File.createTempFile("runtime-events", ".jsonl").also { it.deleteOnExit() }
        file.writeText("")
        val logger = RuntimeEventLogger(
            outputFile = file,
            clock = FakeClock(wallMs = 1000L, monoMs = 2000L),
        )

        logger.log(
            event = "turn.start",
            generationId = 7L,
            elapsedMs = 12L,
            attributes = mapOf(
                "ready" to true,
                "text" to "hello \"offline\"\nvoice",
                "null_field" to null,
            ),
        )

        logger.flush()
        val line = file.readLines().single()
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertTrue(line.contains("\"schema_version\":\"runtime-log-v1\""))
        assertTrue(line.contains("\"seq\":0"))
        assertTrue(line.contains("\"event\":\"turn.start\""))
        assertTrue(line.contains("\"t_wall_ms\":1000"))
        assertTrue(line.contains("\"t_mono_ms\":2000"))
        assertTrue(line.contains("\"generation_id\":7"))
        assertTrue(line.contains("\"elapsed_ms\":12"))
        assertTrue(line.contains("\"ready\":true"))
        assertTrue(line.contains("\"text\":\"hello \\\"offline\\\"\\nvoice\""))
        assertTrue(line.contains("\"null_field\":null"))
    }

    @Test
    fun appendsEventsInOrder() {
        val file = File.createTempFile("runtime-events-order", ".jsonl").also { it.deleteOnExit() }
        file.writeText("")
        val logger = RuntimeEventLogger(
            outputFile = file,
            clock = FakeClock(wallMs = 1L, monoMs = 2L),
        )

        logger.log("session.start")
        logger.log("session.end")
        logger.flush()

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"seq\":0"))
        assertTrue(lines[0].contains("\"event\":\"session.start\""))
        assertTrue(lines[1].contains("\"seq\":1"))
        assertTrue(lines[1].contains("\"event\":\"session.end\""))
    }

    @Test
    fun rotatesTheFileOnceItExceedsTheLimit() {
        val dir = java.nio.file.Files.createTempDirectory("runtime-events-rotate").toFile()
        val file = File(dir, "turn_events.jsonl")
        val logger = RuntimeEventLogger(
            outputFile = file,
            clock = FakeClock(wallMs = 1L, monoMs = 2L),
            maxBytes = 800L,   // one rotation within 12 x ~98 B lines
        )

        for (i in 0 until 12) logger.log("turn.start", attributes = mapOf("i" to i))   // ~100 B each
        logger.flush()

        // The old file moved aside, the live file holds the tail, and nothing was lost.
        val rotated = logger.rotatedFile
        assertTrue(rotated.exists())
        val rotatedLines = rotated.readLines()
        val liveLines = file.readLines()
        assertTrue(rotatedLines.size >= 4)
        assertTrue(liveLines.isNotEmpty())
        assertEquals(12, rotatedLines.size + liveLines.size)
        assertTrue(liveLines.last().contains("\"i\":11"))
        logger.close()
        dir.deleteRecursively()
    }

    @Test
    fun closeMakesTheTailDurableAndDropsLaterEvents() {
        val file = File.createTempFile("runtime-events-close", ".jsonl").also { it.deleteOnExit() }
        file.writeText("")
        val logger = RuntimeEventLogger(outputFile = file, clock = FakeClock(wallMs = 1L, monoMs = 2L))

        logger.log("session.end")
        logger.close()
        logger.log("after.close")

        val lines = file.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"event\":\"session.end\""))
    }

    private class FakeClock(
        private val wallMs: Long,
        private val monoMs: Long,
    ) : RuntimeEventLogger.Clock {
        override fun wallMs(): Long = wallMs
        override fun monoMs(): Long = monoMs
    }
}
