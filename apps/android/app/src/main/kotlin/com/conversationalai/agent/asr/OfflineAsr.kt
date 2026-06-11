package com.conversationalai.agent.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * Owned offline ASR (sherpa-onnx), PER-LANGUAGE engine routing (host CER bench, 2026-05-31):
 *  - KO -> Dolphin base CTC (multi East-Asian). On real music-background KO it beat SenseVoice
 *    (CER 0.60 -> 0.30) and clean KO too (0.12 -> 0.06), at ~27-42 ms (faster). Cannot do English.
 *  - EN -> SenseVoice int8 (excellent English; Dolphin outputs nothing for English).
 * Both fully on-device (ONNX/CPU), chunked/non-streaming. The ko/en toggle selects the engine.
 *
 * [senseVoiceDir] has model.int8.onnx + tokens.txt; [dolphinDir] (optional) likewise. If Dolphin is
 * absent, KO falls back to SenseVoice with language pinned "ko".
 */
class OfflineAsr(
    private val senseVoiceDir: String,
    private val dolphinDir: String? = null,
) : AsrEngine {

    private var recognizer: OfflineRecognizer? = null
    private var backend: String = "?"

    @Volatile
    var language: String = "ko"
        private set

    override fun name(): String = "sherpa-onnx $backend (offline, lang=$language)"

    fun init(language: String = "ko"): Boolean {
        this.language = language
        return build()
    }

    /** Switch language; rebuilds the recognizer (KO->Dolphin / EN->SenseVoice). Returns ok. */
    fun setLanguage(lang: String): Boolean {
        if (lang == language && recognizer != null) return true
        language = lang
        return build()
    }

    private fun build(): Boolean = try {
        recognizer?.release()
        recognizer = null
        val dolphinModel = dolphinDir?.let { File("$it/model.int8.onnx") }
        val useDolphin = language == "ko" && dolphinModel?.exists() == true
        val modelConfig = if (useDolphin) {
            backend = "dolphin"
            OfflineModelConfig(
                dolphin = OfflineDolphinModelConfig(model = "$dolphinDir/model.int8.onnx"),
                tokens = "$dolphinDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            )
        } else {
            backend = "sensevoice"
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$senseVoiceDir/model.int8.onnx",
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = "$senseVoiceDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            )
        }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = modelConfig,
            ),
        )
        Log.i(TAG, "ASR ready (lang=$language, backend=$backend)")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "init failed (lang=$language)", t)
        recognizer = null
        false
    }

    /** Transcribe one mono utterance ([samples] in [-1,1] at [sampleRate]); blocking. */
    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val r = recognizer ?: return ""
        val stream = r.createStream()
        stream.acceptWaveform(samples, sampleRate)
        r.decode(stream)
        val text = r.getResult(stream).text
        stream.release()
        if (!scriptConsistent(text, language)) {
            // Multilingual backends (Dolphin covers ~40 languages) hallucinate foreign scripts
            // on unclear audio (observed: Cyrillic/Chinese for Korean speech). A transcript whose
            // letters are mostly outside the configured language's script is noise — drop it.
            Log.i(TAG, "rejected foreign-script transcript (lang=$language): \"$text\"")
            return ""
        }
        return text
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        private const val TAG = "OfflineAsr"

        /** True when [text]'s letters are predominantly in [language]'s expected scripts.
         *  KO accepts Hangul plus embedded Latin words; EN expects Latin. Pure; JVM-tested. */
        fun scriptConsistent(text: String, language: String): Boolean {
            var hangul = 0
            var latin = 0
            var other = 0
            for (c in text) {
                when {
                    c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' -> hangul++
                    c in 'A'..'Z' || c in 'a'..'z' -> latin++
                    c.isLetter() -> other++   // CJK ideographs, kana, Cyrillic, ...
                }
            }
            val native = if (language == "ko") hangul + latin else latin
            val foreign = if (language == "ko") other else other + hangul
            return foreign <= native
        }
    }
}
