package com.conversationalai.agent.asr

import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAsrTest {

    private class Fake(private val label: String) : AsrEngine {
        var calls = 0
        override fun name() = label
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            calls += 1
            return "$label:${samples.size}"
        }
    }

    @Test
    fun switchDefaultsToTheOwnedEngineAndFlipsOnlyWhenAsked() {
        val owned = Fake("owned")
        val platform = Fake("platform")
        val sw = SwitchableAsr(owned, platform)

        assertEquals("owned", sw.name())
        assertEquals("owned:3", sw.transcribe(FloatArray(3), 16_000))
        assertTrue(sw.selectPlatform(true))
        assertEquals("platform", sw.name())
        assertEquals("platform:2", sw.transcribe(FloatArray(2), 16_000))
        assertTrue(sw.selectPlatform(false))
        assertEquals("owned", sw.name())
        assertEquals(1, owned.calls + platform.calls - 1)   // one call each
    }

    @Test
    fun switchWithoutAPlatformEngineRefusesAndStaysOwned() {
        val owned = Fake("owned")
        val sw = SwitchableAsr(owned, null)
        assertFalse(sw.selectPlatform(true))
        assertFalse(sw.usePlatform)
        assertEquals("owned", sw.name())
    }

    @Test
    fun languageTagsAndInstalledPackMatching() {
        assertEquals("ko-KR", PlatformAsrLogic.bcp47("ko"))
        assertEquals("en-US", PlatformAsrLogic.bcp47("en"))
        assertEquals("ja-JP", PlatformAsrLogic.bcp47("ja-JP"))
        assertTrue(PlatformAsrLogic.languageInstalled("ko", listOf("en-US", "ko-KR")))
        assertTrue(PlatformAsrLogic.languageInstalled("en", listOf("en_GB")))
        assertFalse(PlatformAsrLogic.languageInstalled("ko", listOf("en-US", "ja-JP")))
        assertFalse(PlatformAsrLogic.languageInstalled("ko", emptyList()))
    }

    @Test
    fun floatSamplesBecomeLittleEndianPcm16() {
        val bytes = PlatformAsrLogic.pcm16Bytes(floatArrayOf(0f, 1f, -1f, 0.5f, 2f))
        // 0 -> 0000; 1.0 -> 7FFF; -1.0 -> 8001; 0.5 -> 3FFF; clipped 2.0 -> 7FFF
        assertArrayEquals(
            byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x3F, 0xFF.toByte(), 0x7F),
            bytes,
        )
    }

    @Test
    fun resultPickingAndErrorClassification() {
        assertEquals("hello", PlatformAsrLogic.pickResult(listOf("  ", "hello ", "second")))
        assertEquals("", PlatformAsrLogic.pickResult(null))
        assertEquals("", PlatformAsrLogic.pickResult(emptyList()))
        assertTrue(PlatformAsrLogic.isNoResult(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(PlatformAsrLogic.isNoResult(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(PlatformAsrLogic.isNoResult(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals("language pack not installed", PlatformAsrLogic.errorText(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals("error 99", PlatformAsrLogic.errorText(99))
    }
}
