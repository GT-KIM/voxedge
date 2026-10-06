package com.conversationalai.agent.asr

import android.speech.SpeechRecognizer

/** Pure helpers for [PlatformAsr] (no Android runtime needed; JVM-tested). */
object PlatformAsrLogic {

    /** App language code -> BCP-47 tag the recognizer expects. */
    fun bcp47(language: String): String = when (language.lowercase()) {
        "ko", "ko-kr" -> "ko-KR"
        "en", "en-us" -> "en-US"
        else -> language
    }

    /** True when [installed] (BCP-47 tags from RecognitionSupport) covers [language]. Matches on
     *  the primary subtag so "ko" accepts "ko-KR" and vice versa. */
    fun languageInstalled(language: String, installed: Collection<String>): Boolean {
        val want = primary(bcp47(language))
        return installed.any { primary(it) == want }
    }

    /** Float [-1,1] mono -> little-endian PCM16 bytes, the encoding handed to the recognizer. */
    fun pcm16Bytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** First non-blank hypothesis, or "". */
    fun pickResult(hypotheses: List<String>?): String =
        hypotheses?.firstOrNull { it.isNotBlank() }?.trim() ?: ""

    fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported on device"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not installed"
        else -> "error $code"
    }

    /** Errors that mean "nothing recognized" rather than a broken recognizer. */
    fun isNoResult(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    private fun primary(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()
}
