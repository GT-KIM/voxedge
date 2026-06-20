package com.conversationalai.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.sin

class AudibleFeedbackTest {

    @Test
    fun phrasesAreLocalizedShortAndWithinTheTtsBudget() {
        for (cue in AudibleFeedback.Cue.values()) {
            val ko = AudibleFeedback.phrase(cue, "ko")
            val en = AudibleFeedback.phrase(cue, "en")
            assertTrue(ko.isNotBlank())
            assertTrue(en.isNotBlank())
            assertFalse(ko == en)   // actually localized, not a passthrough
            // Must fit one TTS clause: T=64 NFKD tokens minus the <lang> wrapper overhead (~10).
            assertTrue("ko cue too long: $ko", nfkd(ko) <= 54)
            assertTrue("en cue too long: $en", nfkd(en) <= 54)
        }
        // Unknown language code falls back to English.
        assertEquals(
            AudibleFeedback.phrase(AudibleFeedback.Cue.NOT_UNDERSTOOD, "en"),
            AudibleFeedback.phrase(AudibleFeedback.Cue.NOT_UNDERSTOOD, "fr"),
        )
    }

    @Test
    fun looksLikeSpeechGatesOnDurationAndEnergy() {
        val sr = 16000
        // 1s of loud speech-like energy -> yes.
        val loud = FloatArray(sr) { (0.3 * sin(2.0 * PI * 200 * it / sr)).toFloat() }
        assertTrue(AudibleFeedback.looksLikeSpeech(loud, sr))
        // A short blip (100 ms), even loud -> no (too short to be an utterance).
        assertFalse(AudibleFeedback.looksLikeSpeech(loud.copyOfRange(0, sr / 10), sr))
        // A long but near-silent buffer -> no (background hiss, not speech).
        val quiet = FloatArray(sr) { (0.001 * sin(2.0 * PI * 200 * it / sr)).toFloat() }
        assertFalse(AudibleFeedback.looksLikeSpeech(quiet, sr))
        assertFalse(AudibleFeedback.looksLikeSpeech(FloatArray(0), sr))
        assertFalse(AudibleFeedback.looksLikeSpeech(loud, 0))
    }

    @Test
    fun earconIsBoundedAndFadesAtTheEdges() {
        val sr = 44100
        val pcm = AudibleFeedback.earcon(sr)
        assertEquals(2 * (sr * 150 / 1000), pcm.size)   // two 150 ms tones
        assertTrue(pcm.all { it in -0.23f..0.23f })     // peak ~0.22, never clips
        // Fades: the very first and very last samples sit near zero (no click on the joins).
        assertTrue(kotlin.math.abs(pcm.first()) < 0.02f)
        assertTrue(kotlin.math.abs(pcm.last()) < 0.02f)
    }

    private fun nfkd(s: String): Int = Normalizer.normalize(s, Normalizer.Form.NFKD).length
}
