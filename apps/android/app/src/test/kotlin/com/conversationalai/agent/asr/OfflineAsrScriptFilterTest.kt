package com.conversationalai.agent.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foreign-script rejection (observed live: Dolphin produced Cyrillic/Chinese for Korean audio). */
class OfflineAsrScriptFilterTest {

    // ASCII-only source: samples via escapes.
    private val hangulQuestion = "\uC9C0\uAE08\uBA87\uC774\uC57C"          // Korean
    private val cyrillic = " \u043F\u0440\u043E\u0434\u0430"                      // Cyrillic hallucination (live log)
    private val chinese = "\u897F\u5B89\u554A"                        // Chinese hallucination (live log)
    private val mixedKo = "\uC544\uC774\uD3F0 iPhone \uC88B\uC544"        // Hangul + embedded Latin

    @Test
    fun koreanModeKeepsHangulAndMixedLatin() {
        assertTrue(OfflineAsr.scriptConsistent(hangulQuestion, "ko"))
        assertTrue(OfflineAsr.scriptConsistent(mixedKo, "ko"))
        assertTrue(OfflineAsr.scriptConsistent("", "ko"))
    }

    @Test
    fun koreanModeRejectsForeignScripts() {
        assertFalse(OfflineAsr.scriptConsistent(cyrillic, "ko"))
        assertFalse(OfflineAsr.scriptConsistent(chinese, "ko"))
    }

    @Test
    fun englishModeKeepsLatinRejectsOthers() {
        assertTrue(OfflineAsr.scriptConsistent("what time is it now", "en"))
        assertFalse(OfflineAsr.scriptConsistent(hangulQuestion, "en"))
        assertFalse(OfflineAsr.scriptConsistent(chinese, "en"))
    }
}
